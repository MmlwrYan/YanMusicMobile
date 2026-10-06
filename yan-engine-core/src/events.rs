//! 事件轮询线程 —— 从 mpv 拉事件，分流到 `SharedProps` 与 `EventSink`。
//!
//! 设计沿用桌面版 `native/yan-mpv-player/src/event_loop.rs`，
//! 但把 napi 的 `ThreadsafeFunction` 换成平台无关的 [`EventSink`]。
//!
//! # 分流策略（**卡顿防治的核心**）
//!
//! ```text
//!                          ┌─ 高频属性（time-pos/duration/pause/volume/speed）
//!                          │   → 只写 SharedProps 原子变量（零跨界成本）
//! mpv_wait_event ──────────┤
//!                          ├─ 低频事件（file-loaded/end-file/idle）
//!                          │   → EventSink::emit（跨语言，但频率低）
//!                          │
//!                          └─ 噪声日志（ffmpeg/audio, ad）
//!                              → 源头丢弃，根本不跨线程
//! ```

use std::ffi::CStr;
use std::os::raw::{c_char, c_void};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;

use crate::bridge::{EventSink, EventType, SharedProps};
use crate::mpv_ffi::*;

/// 高频噪声日志前缀：坏帧/解码错误会逐帧上报，诊断价值低但可能成簇爆发。
/// **在事件线程内直接丢弃，根本不跨线程**（沿用桌面版策略）。
const NOISE_LOG_PREFIXES: &[&str] = &["ffmpeg/audio", "ad", "cplayer"];

/// 启动事件轮询线程。
///
/// `handle_addr` 用 usize 传递以绕过 `*mut` 的 `Send` 限制
/// （libmpv 保证 `mpv_handle` 可跨线程使用）。
pub fn start_event_loop(
    lib: Arc<MpvLib>,
    handle_addr: usize,
    props: Arc<SharedProps>,
    shutdown: Arc<AtomicBool>,
    sink: Arc<dyn EventSink>,
) -> JoinHandle<()> {
    std::thread::Builder::new()
        .name("mpv-event-loop".to_string())
        .spawn(move || {
            let handle = handle_addr as *mut MpvHandle;
            run_loop(&lib, handle, &props, &shutdown, &sink);
        })
        .expect("failed to spawn mpv event loop thread")
}

fn run_loop(
    lib: &MpvLib,
    handle: *mut MpvHandle,
    props: &SharedProps,
    shutdown: &AtomicBool,
    sink: &Arc<dyn EventSink>,
) {
    loop {
        if shutdown.load(Ordering::SeqCst) {
            break;
        }

        // 阻塞等事件，超时 0.5 秒（保证 shutdown 能及时响应）
        let ev = unsafe { (lib.mpv_wait_event)(handle, 0.5) };
        if ev.is_null() {
            continue;
        }
        if shutdown.load(Ordering::SeqCst) {
            break;
        }

        let id = unsafe { event_id(ev) };
        match id {
            MPV_EVENT_NONE => continue,

            MPV_EVENT_SHUTDOWN => break,

            MPV_EVENT_PROPERTY_CHANGE => {
                let prop = unsafe { event_data(ev) } as *mut MpvEventProperty;
                if prop.is_null() {
                    continue;
                }
                handle_property_change(prop, props, sink);
            }

            MPV_EVENT_LOG_MESSAGE => {
                let msg = unsafe { event_data(ev) };
                if msg.is_null() {
                    continue;
                }
                handle_log_message(msg, sink);
            }

            MPV_EVENT_END_FILE => {
                let reason = read_end_file_reason(unsafe { event_data(ev) });
                props.set_paused(true);
                sink.emit(EventType::PlaybackEnd, 0.0, 0, reason);
            }

            MPV_EVENT_FILE_LOADED => {
                let path = get_string_property(lib, handle, "path");
                props.set_idle(false);
                // 加载完成后立即刷新一次时长（首帧 duration 可能还没到）
                if let Ok(d) = get_double_property(lib, handle, "duration") {
                    props.set_duration(d);
                }
                sink.emit(EventType::FileLoaded, 0.0, 0, &path);
            }

            MPV_EVENT_IDLE => {
                props.set_idle(true);
                sink.emit(EventType::Idle, 0.0, 0, "");
            }

            _ => {}
        }
    }
}

/// 处理属性变更。
///
/// **高频属性只写 `SharedProps`（原子写，零跨界成本），不回调。**
/// 唯一的例外是 `pause` —— 它是低频且有语义价值的事件，值得通知 UI。
fn handle_property_change(
    prop: *mut MpvEventProperty,
    props: &SharedProps,
    sink: &Arc<dyn EventSink>,
) {
    let name_ptr = unsafe { property_name(prop) };
    if name_ptr.is_null() {
        return;
    }
    let name = unsafe { CStr::from_ptr(name_ptr).to_string_lossy() };

    let format = unsafe { property_format(prop) };
    let data = unsafe { property_data(prop) };
    if data.is_null() {
        return;
    }

    match name.as_ref() {
        // ★ 高频：只写原子量，不跨界
        "time-pos" if format == MPV_FORMAT_DOUBLE => {
            let v = unsafe { *(data as *const f64) };
            props.set_time_pos(v);
        }
        "duration" if format == MPV_FORMAT_DOUBLE => {
            let v = unsafe { *(data as *const f64) };
            props.set_duration(v);
        }
        "volume" if format == MPV_FORMAT_DOUBLE => {
            let v = unsafe { *(data as *const f64) };
            props.set_volume(v);
        }
        "speed" if format == MPV_FORMAT_DOUBLE => {
            let v = unsafe { *(data as *const f64) };
            props.set_speed(v);
        }
        // 低频且有语义：写原子量 + 通知 UI
        "pause" if format == MPV_FORMAT_FLAG => {
            let flag = unsafe { *(data as *const i32) };
            let paused = flag != 0;
            props.set_paused(paused);
            sink.emit(EventType::StateChange, 0.0, i64::from(paused), "");
        }
        "idle-active" if format == MPV_FORMAT_FLAG => {
            let flag = unsafe { *(data as *const i32) };
            props.set_idle(flag != 0);
        }
        _ => {}
    }
}

/// 日志处理：噪声前缀直接丢弃，其余转给 sink。
fn handle_log_message(msg: *mut c_void, sink: &Arc<dyn EventSink>) {
    // mpv_event_log_message { const char *prefix; const char *level; const char *text; ... }
    let base = msg as *const u8;
    let ptr_size = std::mem::size_of::<usize>();
    let prefix = unsafe { read_cstr_at(base, 0) };
    let level = unsafe { read_cstr_at(base, ptr_size) };
    let text = unsafe { read_cstr_at(base, ptr_size * 2) };

    // ★ 噪声源头丢弃 —— 不跨线程
    if NOISE_LOG_PREFIXES.contains(&prefix.as_str()) {
        return;
    }
    if text.is_empty() {
        return;
    }

    // 把 level 编进 arg_i64 的第一个字节（避免为此新增字符串参数）
    let level_code = match level.as_str() {
        "fatal" => 1,
        "error" => 2,
        "warn" => 3,
        "info" => 4,
        "v" => 5,
        "debug" => 6,
        "trace" => 7,
        _ => 0,
    };
    sink.emit(EventType::LogMessage, 0.0, level_code, &text);
}

/// 从结构体基址 + 偏移处读一个 C 字符串。
unsafe fn read_cstr_at(base: *const u8, offset: usize) -> String {
    let ptr_ptr = base.add(offset) as *const *const c_char;
    let p = *ptr_ptr;
    if p.is_null() {
        return String::new();
    }
    CStr::from_ptr(p).to_string_lossy().trim().to_string()
}

/// 读取 end-file 事件的 reason。
fn read_end_file_reason(data: *mut c_void) -> &'static str {
    if data.is_null() {
        return "eof";
    }
    // mpv_event_end_file { int reason; ... }
    let reason = unsafe { *(data as *const i32) };
    match reason {
        MPV_END_FILE_REASON_EOF => "eof",
        MPV_END_FILE_REASON_STOP => "stop",
        MPV_END_FILE_REASON_ERROR => "error",
        _ => "unknown",
    }
}

fn get_string_property(lib: &MpvLib, handle: *mut MpvHandle, name: &str) -> String {
    let c_name = match std::ffi::CString::new(name) {
        Ok(v) => v,
        Err(_) => return String::new(),
    };
    let ptr = unsafe { (lib.mpv_get_property_string)(handle, c_name.as_ptr()) };
    if ptr.is_null() {
        return String::new();
    }
    let value = unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() };
    unsafe { (lib.mpv_free)(ptr as *mut c_void) };
    value
}

fn get_double_property(lib: &MpvLib, handle: *mut MpvHandle, name: &str) -> Result<f64, ()> {
    let c_name = std::ffi::CString::new(name).map_err(|_| ())?;
    let mut out: f64 = 0.0;
    let rc = unsafe {
        (lib.mpv_get_property)(
            handle,
            c_name.as_ptr(),
            MPV_FORMAT_DOUBLE,
            &mut out as *mut f64 as *mut c_void,
        )
    };
    if rc < 0 {
        return Err(());
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noise_prefixes_are_defined() {
        // 这些前缀是「源头丢弃」的依据，不能被清空
        assert!(NOISE_LOG_PREFIXES.contains(&"ffmpeg/audio"));
        assert!(NOISE_LOG_PREFIXES.contains(&"ad"));
    }

    #[test]
    fn end_file_reason_mapping() {
        assert_eq!(read_end_file_reason(std::ptr::null_mut()), "eof");
    }
}
