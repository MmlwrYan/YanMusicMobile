//! # 纯 C ABI 绑定层（为 iOS / Swift 预留）
//!
//! **本文件存在的意义：证明「核心层与平台无关」这个设计是真的可行。**
//!
//! 它不 import `jni`，也不 import 任何 Apple 框架 —— 只导出普通的
//! `extern "C"` 函数。iOS 侧用 Swift 直接调用这些符号即可。
//!
//! ```text
//!   yan-engine-core（同一份代码）
//!          △
//!    ┌─────┴─────┐
//!  jni 层      cabi 层
//!  (Android)   (iOS/Swift)
//! ```
//!
//! # 内存管理约定（Swift 侧必读）
//!
//! - `yan_engine_create` 返回 `*mut c_void`（不透明句柄），失败返回 null。
//! - 调用方必须最终调 `yan_engine_destroy(handle)` 释放。
//! - `yan_engine_take_last_error()` 返回的字符串由 **Rust 分配**，
//!   用完必须调 `yan_engine_free_string(ptr)` —— **不要让 Swift 自己 free**。

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_longlong, c_void};
use std::sync::{Arc, Mutex};

use yan_engine_core::bridge::{EventSink, EventType, SharedProps};
use yan_engine_core::engine::{Engine, EngineConfig};

/// C 侧的事件回调函数指针类型。
///
/// Swift 侧签名：
/// ```swift
/// typealias YanEventCallback = @convention(c)
///     (Int32, Double, Int64, UnsafePointer<CChar>?) -> Void
/// ```
pub type YanEventCallback =
    extern "C" fn(event: i32, arg_f64: c_double, arg_i64: c_longlong, text: *const c_char);

/// 把 C 回调包成 `EventSink`。
struct CCallbackSink {
    callback: YanEventCallback,
}

unsafe impl Send for CCallbackSink {}
unsafe impl Sync for CCallbackSink {}

impl EventSink for CCallbackSink {
    fn emit(&self, event: EventType, arg_f64: f64, arg_i64: i64, text: &str) {
        // ⚠️ Swift 侧必须自行 dispatch 到 main（本调用不在主线程）
        let c_text = CString::new(text).unwrap_or_default();
        (self.callback)(
            event.as_i32(),
            arg_f64,
            arg_i64 as c_longlong,
            c_text.as_ptr(),
        );
    }
}

/// 引擎句柄（对 Swift 不透明）。
pub struct YanEngineHandle {
    engine: Arc<Engine>,
    props: Arc<SharedProps>,
}

/// 最近一次错误的字符串（供 `yan_engine_take_last_error` 取）。
static LAST_ERROR: Mutex<Option<CString>> = Mutex::new(None);

fn set_last_error(msg: &str) {
    if let Ok(mut g) = LAST_ERROR.lock() {
        *g = CString::new(msg).ok();
    }
}

// ══════════════════════════════════════════════════════════════
// 生命周期
// ══════════════════════════════════════════════════════════════

/// 创建引擎。
///
/// - `libmpv_path`：传 null 或空串表示静态链接（iOS 常见）
/// - `audio_output`：`"audiounit"`（iOS）/ `"audiotrack"`（Android）
/// - `callback`：事件回调（可传 null 表示不关心事件）
///
/// 返回不透明句柄；失败返回 null，错误信息用 `yan_engine_take_last_error` 取。
///
/// # Safety
/// `libmpv_path` / `audio_output` 若非 null，必须是有效的 NUL 结尾字符串。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_create(
    libmpv_path: *const c_char,
    audio_output: *const c_char,
    callback: Option<YanEventCallback>,
) -> *mut c_void {
    let path = if libmpv_path.is_null() {
        String::new()
    } else {
        CStr::from_ptr(libmpv_path).to_string_lossy().into_owned()
    };
    let ao = if audio_output.is_null() {
        "audiounit".to_string()
    } else {
        CStr::from_ptr(audio_output).to_string_lossy().into_owned()
    };

    let sink: Arc<dyn EventSink> = match callback {
        Some(cb) => Arc::new(CCallbackSink { callback: cb }),
        None => Arc::new(yan_engine_core::bridge::NullEventSink),
    };

    let props = SharedProps::new();
    let cfg = EngineConfig {
        libmpv_path: path,
        audio_output: ao,
        audio_enabled: true,
        video_disabled: true,
        sink,
        props: props.clone(),
    };

    match Engine::create(cfg) {
        Ok(engine) => {
            let handle = Box::new(YanEngineHandle {
                engine: Arc::new(engine),
                props,
            });
            Box::into_raw(handle) as *mut c_void
        }
        Err(e) => {
            set_last_error(&e);
            std::ptr::null_mut()
        }
    }
}

/// 销毁引擎并释放句柄。
///
/// # Safety
/// `handle` 必须是 `yan_engine_create` 返回且未被销毁的指针。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_destroy(handle: *mut c_void) {
    if handle.is_null() {
        return;
    }
    let h = Box::from_raw(handle as *mut YanEngineHandle);
    h.engine.shutdown();
    // Box 在此 drop
}

// ══════════════════════════════════════════════════════════════
// 播放控制
// ══════════════════════════════════════════════════════════════

/// 加载文件/URL。返回 0 成功。
///
/// # Safety
/// `handle` 必须有效；`uri` 必须是 NUL 结尾字符串。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_load(handle: *mut c_void, uri: *const c_char) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    if uri.is_null() {
        return -1;
    }
    let uri = CStr::from_ptr(uri).to_string_lossy();
    match h.engine.load(&uri) {
        Ok(_) => 0,
        Err(e) => {
            set_last_error(&e);
            -1
        }
    }
}

/// 播放/暂停切换。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_toggle_pause(handle: *mut c_void) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    match h.engine.toggle_pause() {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// 停止。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_stop(handle: *mut c_void) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    match h.engine.stop() {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// 绝对跳转（秒）。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_seek_absolute(handle: *mut c_void, seconds: c_double) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    match h.engine.seek_absolute(seconds) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// 设置音量（0–100）。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_set_volume(handle: *mut c_void, volume: c_double) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    match h.engine.set_volume(volume) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// 设置 EQ / 音效滤镜链（mpv `af` 参数）。
///
/// # Safety
/// `handle` 必须有效；`chain` 必须是 NUL 结尾字符串。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_set_audio_filters(
    handle: *mut c_void,
    chain: *const c_char,
) -> i32 {
    let h = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h,
        None => return -1,
    };
    if chain.is_null() {
        return -1;
    }
    let chain = CStr::from_ptr(chain).to_string_lossy();
    match h.engine.set_audio_filters(&chain) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

// ══════════════════════════════════════════════════════════════
// 高频属性读取
// ══════════════════════════════════════════════════════════════

/// 当前播放位置（毫秒）。返回 -1 表示句柄无效。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_time_pos_ms(handle: *mut c_void) -> c_longlong {
    match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h.props.time_pos_ms() as c_longlong,
        None => -1,
    }
}

/// 总时长（毫秒）。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_duration_ms(handle: *mut c_void) -> c_longlong {
    match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h.props.duration_ms() as c_longlong,
        None => -1,
    }
}

/// 是否暂停。返回 1/0。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_is_paused(handle: *mut c_void) -> i32 {
    match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => i32::from(h.props.is_paused()),
        None => -1,
    }
}

/// 是否空闲。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_is_idle(handle: *mut c_void) -> i32 {
    match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => i32::from(h.props.is_idle()),
        None => -1,
    }
}

// ══════════════════════════════════════════════════════════════
// 音频参数（高解析验证）
// ══════════════════════════════════════════════════════════════

/// 读取音频采样率。返回 0 表示不可用。
///
/// # Safety
/// `handle` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_audio_samplerate(handle: *mut c_void) -> i32 {
    match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h.engine.audio_params().map(|p| p.samplerate).unwrap_or(0),
        None => 0,
    }
}

/// 读取音频编码名（调用方负责 free）。
///
/// # Safety
/// `handle` 必须有效。返回指针需用 `yan_engine_free_string` 释放。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_audio_codec(handle: *mut c_void) -> *mut c_char {
    let codec = match (handle as *mut YanEngineHandle).as_ref() {
        Some(h) => h
            .engine
            .audio_params()
            .map(|p| p.codec)
            .unwrap_or_default(),
        None => return std::ptr::null_mut(),
    };
    match CString::new(codec) {
        Ok(c) => c.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ══════════════════════════════════════════════════════════════
// 内存 / 错误
// ══════════════════════════════════════════════════════════════

/// 释放由本库分配的字符串。
///
/// # Safety
/// `ptr` 必须来自本库（`yan_engine_audio_codec` / `yan_engine_take_last_error`），
/// 且只能释放一次。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = CString::from_raw(ptr);
    }
}

/// 取走最近一次错误信息（取走后清空）。无错误返回 null。
///
/// # Safety
/// 返回指针需用 `yan_engine_free_string` 释放。
#[no_mangle]
pub unsafe extern "C" fn yan_engine_take_last_error() -> *mut c_char {
    match LAST_ERROR.lock() {
        Ok(mut g) => match g.take() {
            Some(c) => CString::new(c.to_string_lossy().as_bytes())
                .map(|c| c.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            None => std::ptr::null_mut(),
        },
        Err(_) => std::ptr::null_mut(),
    }
}

/// 引擎版本字符串（静态，不需要 free）。
#[no_mangle]
pub extern "C" fn yan_engine_version() -> *const c_char {
    // 用 leak 方式给出 'static 生命周期 —— 版本号是常量，只泄一次
    static VERSION: std::sync::OnceLock<CString> = std::sync::OnceLock::new();
    let s = VERSION.get_or_init(|| CString::new(yan_engine_core::ENGINE_VERSION).unwrap());
    s.as_ptr()
}

/// 事件类型编码查询（供 Swift 侧断言契约）。
#[no_mangle]
pub extern "C" fn yan_event_type_time_update() -> i32 {
    EventType::TimeUpdate.as_i32()
}

/// # Safety
/// 无。
#[no_mangle]
pub extern "C" fn yan_event_type_file_loaded() -> i32 {
    EventType::FileLoaded.as_i32()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn null_handle_is_handled_gracefully() {
        unsafe {
            assert_eq!(yan_engine_toggle_pause(std::ptr::null_mut()), -1);
            assert_eq!(yan_engine_time_pos_ms(std::ptr::null_mut()), -1);
            assert_eq!(yan_engine_is_paused(std::ptr::null_mut()), -1);
            assert_eq!(yan_engine_is_idle(std::ptr::null_mut()), -1);
            yan_engine_destroy(std::ptr::null_mut()); // 不应 panic
        }
    }

    #[test]
    fn create_fails_with_error_message_when_lib_missing() {
        unsafe {
            let bogus = CString::new("/nonexistent/libmpv.so").unwrap();
            let handle = yan_engine_create(
                bogus.as_ptr(),
                std::ptr::null(),
                None,
            );
            assert!(handle.is_null(), "库不存在时应返回 null");

            let err_ptr = yan_engine_take_last_error();
            assert!(!err_ptr.is_null(), "应留下错误信息");
            let msg = CStr::from_ptr(err_ptr).to_string_lossy().into_owned();
            assert!(msg.contains("加载 libmpv 失败"), "错误信息应可读：{msg}");
            yan_engine_free_string(err_ptr);

            // 取走后应清空
            assert!(yan_engine_take_last_error().is_null());
        }
    }

    #[test]
    fn version_is_stable_pointer() {
        let a = yan_engine_version();
        let b = yan_engine_version();
        assert_eq!(a, b, "版本指针应稳定（OnceLock 缓存）");
        let s = unsafe { CStr::from_ptr(a).to_string_lossy().into_owned() };
        assert_eq!(s, "0.1.0");
    }

    #[test]
    fn event_type_contract_matches_core() {
        assert_eq!(yan_event_type_time_update(), 1);
        assert_eq!(yan_event_type_file_loaded(), 5);
    }

    #[test]
    fn free_null_string_is_safe() {
        unsafe { yan_engine_free_string(std::ptr::null_mut()) };
    }
}
