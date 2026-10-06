//! 引擎生命周期与命令封装。
//!
//! 这是 `yan-engine-core` 对外的门面 —— 各平台绑定层只与 [`Engine`] 打交道。

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use crate::bridge::{EventSink, EventType, SharedProps};
use crate::events::start_event_loop;
use crate::mpv_ffi::*;

/// 引擎配置。
///
/// 各平台差异全部收在这里 —— **不要在别的模块里写 `#[cfg(target_os)]`**。
#[derive(Clone)]
pub struct EngineConfig {
    /// libmpv 动态库路径。
    /// - Android：`"libmpv.so"`
    /// - iOS（静态链接）：留空则不走 dlopen
    pub libmpv_path: String,
    /// 音频输出后端。**平台相关**：
    /// - Android：`"audiotrack"` 或 `"aaudio"`
    /// - iOS：`"audiounit"`
    /// - 桌面：`"auto"`
    pub audio_output: String,
    /// 是否启用音频（Android 上无视频，恒 true）
    pub audio_enabled: bool,
    /// 是否禁用视频（移动端音频 App 恒 true）
    pub video_disabled: bool,
    /// 事件回调
    pub sink: Arc<dyn EventSink>,
    /// 高频属性共享单元
    pub props: Arc<SharedProps>,
}

impl std::fmt::Debug for EngineConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EngineConfig")
            .field("libmpv_path", &self.libmpv_path)
            .field("audio_output", &self.audio_output)
            .field("audio_enabled", &self.audio_enabled)
            .field("video_disabled", &self.video_disabled)
            .finish()
    }
}

/// 每个平台的推荐默认配置。
impl EngineConfig {
    pub fn for_android(sink: Arc<dyn EventSink>, props: Arc<SharedProps>) -> Self {
        Self {
            libmpv_path: "libmpv.so".to_string(),
            audio_output: "audiotrack".to_string(),
            audio_enabled: true,
            video_disabled: true,
            sink,
            props,
        }
    }

    pub fn for_ios(sink: Arc<dyn EventSink>, props: Arc<SharedProps>) -> Self {
        Self {
            // iOS 静态链接：空路径表示不走 dlopen（由绑定层直接给符号）
            libmpv_path: String::new(),
            audio_output: "audiounit".to_string(),
            audio_enabled: true,
            video_disabled: true,
            sink,
            props,
        }
    }
}

/// 引擎实例。
pub struct Engine {
    lib: Arc<MpvLib>,
    handle: *mut MpvHandle,
    props: Arc<SharedProps>,
    shutdown: Arc<AtomicBool>,
    event_thread: Mutex<Option<JoinHandle<()>>>,
    config: EngineConfig,
    /// 防止 `Drop` 之外被重复销毁
    destroyed: AtomicBool,
}

// mpv_handle 的线程安全性由 libmpv 文档保证
unsafe impl Send for Engine {}
unsafe impl Sync for Engine {}

impl Engine {
    /// 创建并初始化引擎。
    pub fn create(config: EngineConfig) -> Result<Self, String> {
        let lib = unsafe { MpvLib::load(&config.libmpv_path) }
            .map_err(|e| format!("加载 libmpv 失败：{e}"))?;

        let handle = unsafe { (lib.mpv_create)() };
        if handle.is_null() {
            return Err("mpv_create 返回空指针".to_string());
        }

        // ── 在 initialize 之前设置 option ──
        engine_set_option(&lib, handle, "config", "no")?; // 不读用户配置文件
        engine_set_option(&lib, handle, "terminal", "no")?;
        engine_set_option(&lib, handle, "input-default-bindings", "no")?;
        engine_set_option(&lib, handle, "input-vo-keyboard", "no")?;

        if config.video_disabled {
            engine_set_option(&lib, handle, "vid", "no")?;
            engine_set_option(&lib, handle, "vo", "null")?;
        }
        if config.audio_enabled {
            engine_set_option(&lib, handle, "ao", &config.audio_output)?;
            engine_set_option(&lib, handle, "audio-display", "no")?;
        }

        // ── initialize ──
        let rc = unsafe { (lib.mpv_initialize)(handle) };
        if rc < 0 {
            let msg = engine_error_string(&lib, rc);
            unsafe { (lib.mpv_terminate_destroy)(handle) };
            return Err(format!("mpv_initialize 失败（rc={rc}）：{msg}"));
        }

        // ── 观察高频属性 ──
        // 注意：time-pos 观察频率高，写入的是 SharedProps 原子变量，
        // 不逐次跨语言回调（见 SharedProps 文档）。
        unsafe {
            (lib.mpv_observe_property)(handle, 0, cstr("time-pos").as_ptr(), MPV_FORMAT_DOUBLE);
            (lib.mpv_observe_property)(handle, 0, cstr("duration").as_ptr(), MPV_FORMAT_DOUBLE);
            (lib.mpv_observe_property)(handle, 0, cstr("pause").as_ptr(), MPV_FORMAT_FLAG);
            (lib.mpv_observe_property)(handle, 0, cstr("volume").as_ptr(), MPV_FORMAT_DOUBLE);
            (lib.mpv_observe_property)(handle, 0, cstr("speed").as_ptr(), MPV_FORMAT_DOUBLE);
            (lib.mpv_observe_property)(handle, 0, cstr("idle-active").as_ptr(), MPV_FORMAT_FLAG);
        }

        let lib = Arc::new(lib);
        let shutdown = Arc::new(AtomicBool::new(false));

        let event_thread = start_event_loop(
            lib.clone(),
            handle as usize,
            config.props.clone(),
            shutdown.clone(),
            config.sink.clone(),
        );

        Ok(Self {
            lib,
            handle,
            props: config.props.clone(),
            shutdown,
            event_thread: Mutex::new(Some(event_thread)),
            config,
            destroyed: AtomicBool::new(false),
        })
    }

    /// 当前配置（只读）。
    pub fn config(&self) -> &EngineConfig {
        &self.config
    }

    /// 高频属性句柄。
    pub fn props(&self) -> &Arc<SharedProps> {
        &self.props
    }

    /// 加载并播放一个文件/URL。
    pub fn load(&self, uri: &str) -> Result<(), String> {
        self.command(&["loadfile", uri])?;
        self.props.set_idle(false);
        Ok(())
    }

    /// 播放/暂停切换。
    pub fn toggle_pause(&self) -> Result<(), String> {
        let paused = self.props.is_paused();
        self.set_property_string("pause", if paused { "no" } else { "yes" })?;
        Ok(())
    }

    /// 显式设置暂停。
    pub fn set_paused(&self, paused: bool) -> Result<(), String> {
        self.set_property_string("pause", if paused { "yes" } else { "no" })?;
        Ok(())
    }

    /// 停止播放。
    pub fn stop(&self) -> Result<(), String> {
        self.command(&["stop"])
    }

    /// 跳转到指定秒数。
    pub fn seek_absolute(&self, seconds: f64) -> Result<(), String> {
        let v = format!("{seconds}");
        self.command(&["seek", &v, "absolute"])
    }

    /// 相对跳转（秒，可负）。
    pub fn seek_relative(&self, delta_seconds: f64) -> Result<(), String> {
        let v = format!("{delta_seconds}");
        self.command(&["seek", &v, "relative"])
    }

    /// 设置音量（0–100）。
    pub fn set_volume(&self, volume: f64) -> Result<(), String> {
        let clamped = volume.clamp(0.0, 100.0);
        self.set_property_string("volume", &format!("{clamped}"))?;
        self.props.set_volume(clamped);
        Ok(())
    }

    /// 设置播放速率。
    pub fn set_speed(&self, speed: f64) -> Result<(), String> {
        let clamped = speed.clamp(0.25, 4.0);
        self.set_property_string("speed", &format!("{clamped}"))?;
        self.props.set_speed(clamped);
        Ok(())
    }

    /// ★ 应用 EQ / 空间音效滤镜链（对应桌面版的 `af` 命令）。
    ///
    /// `chain` 是 mpv `af` 参数的完整滤镜链字符串，例如：
    /// ```text
    /// equalizer=f=100:t=q:w=1:g=3,equalizer=f=1000:t=q:w=1:g=-2
    /// ```
    ///
    /// ⚠️ **设置 `af` 会触发 mpv 重建滤镜链，`afir` 卷积链重建很重**
    /// （桌面版 `lib.rs` 有同款注释与工作线程处理）。调用方应在
    /// 非 UI 线程调用，或接受一次短暂卡顿。
    pub fn set_audio_filters(&self, chain: &str) -> Result<(), String> {
        if chain.is_empty() {
            self.set_property_string("af", "")?;
        } else {
            self.set_property_string("af", chain)?;
        }
        Ok(())
    }

    /// 清除所有音频滤镜。
    pub fn clear_audio_filters(&self) -> Result<(), String> {
        self.set_property_string("af", "")
    }

    /// 读取任意字符串属性。
    pub fn get_property_string(&self, name: &str) -> Result<String, String> {
        let c_name = cstr(name);
        let ptr = unsafe { (self.lib.mpv_get_property_string)(self.handle, c_name.as_ptr()) };
        if ptr.is_null() {
            return Err(format!("属性 {name} 不可读"));
        }
        let value = unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() };
        unsafe { (self.lib.mpv_free)(ptr as *mut c_void) };
        Ok(value)
    }

    /// 读取双精度属性。
    pub fn get_property_f64(&self, name: &str) -> Result<f64, String> {
        let c_name = cstr(name);
        let mut out: f64 = 0.0;
        let rc = unsafe {
            (self.lib.mpv_get_property)(
                self.handle,
                c_name.as_ptr(),
                MPV_FORMAT_DOUBLE,
                &mut out as *mut f64 as *mut c_void,
            )
        };
        if rc < 0 {
            return Err(format!("属性 {name} 不可读（rc={rc}）"));
        }
        Ok(out)
    }

    /// 读取整数属性。
    pub fn get_property_i64(&self, name: &str) -> Result<i64, String> {
        let c_name = cstr(name);
        let mut out: i64 = 0;
        let rc = unsafe {
            (self.lib.mpv_get_property)(
                self.handle,
                c_name.as_ptr(),
                MPV_FORMAT_INT64,
                &mut out as *mut i64 as *mut c_void,
            )
        };
        if rc < 0 {
            return Err(format!("属性 {name} 不可读（rc={rc}）"));
        }
        Ok(out)
    }

    /// 读取布尔属性。
    pub fn get_property_bool(&self, name: &str) -> Result<bool, String> {
        let c_name = cstr(name);
        let mut out: c_int = 0;
        let rc = unsafe {
            (self.lib.mpv_get_property)(
                self.handle,
                c_name.as_ptr(),
                MPV_FORMAT_FLAG,
                &mut out as *mut c_int as *mut c_void,
            )
        };
        if rc < 0 {
            return Err(format!("属性 {name} 不可读（rc={rc}）"));
        }
        Ok(out != 0)
    }

    /// **★ 高解析验证关键接口**：读取当前音频流的实际参数。
    ///
    /// 返回 `(采样率, 声道数, 位深/编码名)`。
    /// 用于验证「24bit/96kHz FLAC 未被降采样」。
    pub fn audio_params(&self) -> Result<AudioParams, String> {
        // audio-params 是个 mpv 节点，拆成子属性读更稳
        let samplerate = self.get_property_i64("audio-params/samplerate").unwrap_or(0);
        let channels = self.get_property_i64("audio-params/channel-count").unwrap_or(0);
        let format = self
            .get_property_string("audio-params/format")
            .unwrap_or_default();
        let codec = self
            .get_property_string("audio-codec-name")
            .unwrap_or_default();
        if samplerate == 0 && codec.is_empty() {
            return Err("音频参数尚不可用（文件可能未加载完成）".to_string());
        }
        Ok(AudioParams {
            samplerate: samplerate as i32,
            channels: channels as i32,
            format,
            codec,
        })
    }

    /// 发送任意 mpv 命令（可变参数）。
    pub fn command(&self, args: &[&str]) -> Result<(), String> {
        let c_args: Vec<CString> = args
            .iter()
            .map(|a| CString::new(*a).unwrap_or_default())
            .collect();
        let mut ptrs: Vec<*const c_char> = c_args.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(ptr::null());

        let rc = unsafe { (self.lib.mpv_command)(self.handle, ptrs.as_ptr()) };
        if rc < 0 {
            return Err(format!(
                "命令 {:?} 失败（rc={rc}）：{}",
                args,
                engine_error_string(&self.lib, rc)
            ));
        }
        Ok(())
    }

    /// 发送任意 mpv 命令（字符串形式）。
    pub fn command_string(&self, cmd: &str) -> Result<(), String> {
        let c_cmd = cstr(cmd);
        let rc = unsafe { (self.lib.mpv_command_string)(self.handle, c_cmd.as_ptr()) };
        if rc < 0 {
            return Err(format!(
                "命令字符串 {cmd} 失败（rc={rc}）：{}",
                engine_error_string(&self.lib, rc)
            ));
        }
        Ok(())
    }

    fn set_property_string(&self, name: &str, value: &str) -> Result<(), String> {
        let c_name = cstr(name);
        let c_value = cstr(value);
        let rc = unsafe {
            (self.lib.mpv_set_property_string)(self.handle, c_name.as_ptr(), c_value.as_ptr())
        };
        if rc < 0 {
            return Err(format!(
                "设置属性 {name}={value} 失败（rc={rc}）：{}",
                engine_error_string(&self.lib, rc)
            ));
        }
        Ok(())
    }

    /// 显式关闭引擎（也可依赖 `Drop`）。
    pub fn shutdown(&self) {
        if self.destroyed.swap(true, Ordering::SeqCst) {
            return;
        }
        self.shutdown.store(true, Ordering::SeqCst);
        unsafe {
            (self.lib.mpv_wakeup)(self.handle);
        }
        if let Ok(mut guard) = self.event_thread.lock() {
            if let Some(t) = guard.take() {
                let _ = t.join();
            }
        }
        unsafe {
            (self.lib.mpv_terminate_destroy)(self.handle);
        }
        self.config
            .sink
            .emit(EventType::Idle, 0.0, 1, "engine-shutdown");
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        self.shutdown();
    }
}

/// 音频流参数（高解析验证用）。
#[derive(Debug, Clone, PartialEq)]
pub struct AudioParams {
    pub samplerate: i32,
    pub channels: i32,
    pub format: String,
    pub codec: String,
}

impl AudioParams {
    /// 是否达到「高解析」标准（≥ 88.2kHz 且 ≥ 24bit 或无损编码）。
    pub fn is_hires(&self) -> bool {
        self.samplerate >= 88200
    }
}

// ── 内部工具 ──

fn cstr(s: &str) -> CString {
    CString::new(s).unwrap_or_default()
}

fn engine_set_option(
    lib: &MpvLib,
    handle: *mut MpvHandle,
    name: &str,
    value: &str,
) -> Result<(), String> {
    let c_name = cstr(name);
    let c_value = cstr(value);
    let rc = unsafe { (lib.mpv_set_option_string)(handle, c_name.as_ptr(), c_value.as_ptr()) };
    if rc < 0 {
        return Err(format!(
            "设置 option {name}={value} 失败（rc={rc}）：{}",
            engine_error_string(lib, rc)
        ));
    }
    Ok(())
}

/// 把 mpv 错误码翻译成可读字符串。
fn engine_error_string(lib: &MpvLib, rc: c_int) -> String {
    let ptr = unsafe { (lib.mpv_error_string)(rc) };
    if ptr.is_null() {
        return format!("未知错误码 {rc}");
    }
    unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() }
}

/// 供测试/诊断：读取 libmpv 的 API 版本。
pub fn libmpv_api_version(lib_path: &str) -> Result<u64, String> {
    let lib = unsafe { MpvLib::load(lib_path) }?;
    Ok(unsafe { (lib.mpv_client_api_version)() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::NullEventSink;

    const FAKE_LIB_PATH: &str = "/nonexistent/libmpv.so";

    #[test]
    fn create_fails_gracefully_when_lib_missing() {
        let cfg = EngineConfig {
            libmpv_path: FAKE_LIB_PATH.to_string(),
            audio_output: "auto".to_string(),
            audio_enabled: true,
            video_disabled: true,
            sink: Arc::new(NullEventSink),
            props: SharedProps::new(),
        };
        // 必须返回 Err，而不是 panic —— 移动端加载失败要能优雅降级
        let result = Engine::create(cfg);
        assert!(result.is_err(), "库不存在时应返回 Err");
        let msg = result.err().unwrap();
        assert!(msg.contains("加载 libmpv 失败"), "错误信息应说明是加载失败：{msg}");
    }

    #[test]
    fn is_libmpv_available_returns_false_for_bogus_path() {
        assert!(!is_libmpv_available(FAKE_LIB_PATH));
    }

    #[test]
    fn android_config_uses_correct_defaults() {
        let cfg = EngineConfig::for_android(
            Arc::new(NullEventSink),
            SharedProps::new(),
        );
        assert_eq!(cfg.libmpv_path, "libmpv.so");
        assert_eq!(cfg.audio_output, "audiotrack");
        assert!(cfg.video_disabled, "移动端音频 App 必须默认禁用视频");
        assert!(cfg.audio_enabled);
    }

    #[test]
    fn ios_config_uses_correct_defaults() {
        let cfg = EngineConfig::for_ios(Arc::new(NullEventSink), SharedProps::new());
        assert_eq!(cfg.audio_output, "audiounit");
        assert!(cfg.libmpv_path.is_empty(), "iOS 静态链接不应走 dlopen");
        assert!(cfg.video_disabled);
    }

    #[test]
    fn audio_params_hires_detection() {
        let hires = AudioParams {
            samplerate: 96000,
            channels: 2,
            format: "s32".to_string(),
            codec: "flac".to_string(),
        };
        assert!(hires.is_hires(), "96kHz 应判定为高解析");

        let cd = AudioParams {
            samplerate: 44100,
            channels: 2,
            format: "s16".to_string(),
            codec: "flac".to_string(),
        };
        assert!(!cd.is_hires(), "44.1kHz 不应判定为高解析");

        let boundary = AudioParams {
            samplerate: 88200,
            channels: 2,
            format: "s24".to_string(),
            codec: "flac".to_string(),
        };
        assert!(boundary.is_hires(), "88.2kHz 应判定为高解析（边界值）");
    }
}
