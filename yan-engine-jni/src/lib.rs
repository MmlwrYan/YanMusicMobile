//! # Android JNI 绑定层
//!
//! 把 [`yan_engine_core::Engine`] 暴露给 Kotlin。
//!
//! ## 方法命名约定
//!
//! JNI 要求 native 方法名与 Java 类全限定名严格对应：
//! `Java_<包名下划线化>_<类名>_<方法名>`
//!
//! 本项目约定 Kotlin 侧为：
//! ```text
//! package com.yanmusic.engine
//! object MpvEngine { external fun nativeXxx(...) }
//! ```
//! → JNI 符号：`Java_com_yanmusic_engine_MpvEngine_nativeXxx`
//!
//! ⚠️ **若 Kotlin 侧改了包名或类名，这里必须同步改**，否则运行时报
//! `UnsatisfiedLinkError`。

use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};

use yan_engine_core::bridge::{EventSink, EventType, SharedProps};
use yan_engine_core::engine::{Engine, EngineConfig};

/// 全局持有的引擎句柄（单例模式）。
///
/// 安卓 App 通常只需要一个播放引擎实例。用 `Mutex<Option<...>>`
/// 而非裸指针，保证：
/// - 线程安全
/// - `create` 重复调用不会泄漏（旧的会先 drop）
static ENGINE: Mutex<Option<Arc<Engine>>> = Mutex::new(None);

/// ── JNI 侧的事件 sink ──
///
/// 持有 `JavaVM` 与 Kotlin 回调对象的全局引用。
/// `emit` 被 Rust 事件线程调用 → `AttachCurrentThread` → 调 Kotlin 方法。
struct JniEventSink {
    vm: JavaVM,
    callback: GlobalRef,
}

// JNI 的 JavaVM 与 GlobalRef 在 JNI 层面线程安全（需 Attach 后使用）
unsafe impl Send for JniEventSink {}
unsafe impl Sync for JniEventSink {}

impl JniEventSink {
    /// Kotlin 回调接口的方法签名（编译期固定，避免每次查找开销）。
    ///
    /// 对应 Kotlin：
    /// ```kotlin
    /// interface MpvEventCallback {
    ///     fun onEvent(type: Int, argF64: Double, argI64: Long, text: String?)
    /// }
    /// ```
    const METHOD: &'static str = "onEvent";
    const SIG: &'static str = "(IDJLjava/lang/String;)V";
}

impl EventSink for JniEventSink {
    fn emit(&self, event: EventType, arg_f64: f64, arg_i64: i64, text: &str) {
        // ⚠️ 本函数在 Rust 事件线程执行 —— 必须 Attach
        let mut env = match self.vm.attach_current_thread() {
            Ok(env) => env,
            Err(_) => return, // Attach 失败就丢弃事件，绝不 panic（会拖垮播放）
        };

        // 构造 Java String（可能分配，但事件频率低；高频属性不走这里）
        let jtext: JObject = match env.new_string(text) {
            Ok(s) => s.into(),
            Err(_) => return,
        };

        // 调用 Kotlin：onEvent(type, argF64, argI64, text)
        let result = env.call_method(
            self.callback.as_obj(),
            Self::METHOD,
            Self::SIG,
            &[
                JValue::Int(event.as_i32()),
                JValue::Double(arg_f64),
                JValue::Long(arg_i64),
                JValue::Object(&jtext),
            ],
        );

        // 回调抛异常时清除，避免污染后续 JNI 调用
        if result.is_err() && env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
    }
}

/// 从全局单例取引擎执行 `$body`；引擎不存在 / 锁中毒时直接返回 -1。
///
/// 我们用「全局单例」而非把指针编码进 jlong，因为：
/// - 单引擎场景不需要多实例
/// - 避免指针泄漏到 Java 侧被误用
///
/// # 用法
///
/// ```ignore
/// with_engine!(engine, {
///     engine.load(&uri)   // engine: Arc<Engine>，已 clone 出来、锁已释放
/// })
/// ```
///
/// # ⚠️ 为什么把 engine 绑给调用方（而不是让调用方自己再锁一次）
///
/// 旧写法是宏内只做「存在性检查」，body 里再写一遍
/// `ENGINE.lock().unwrap().as_ref().unwrap().xxx()`。这有两个真实缺陷：
///
/// 1. **绕过中毒检查**：宏用 `match ENGINE.lock()` 处理了 `PoisonError`，
///    但 body 里重锁时用的 `.unwrap()` 会把中毒直接变成 panic。
/// 2. **TOCTOU 竞态**：宏释放锁后，若另一线程正好执行 `nativeDestroy`
///    把 `ENGINE` 置回 `None`，body 里的 `.as_ref().unwrap()` 会 panic。
///
/// 而 **panic 跨越 `extern "system"` 边界是 UB** ——
/// 对播放器而言就是一个硬崩溃，而不是一个可处理的错误码。
/// 因此把 engine 一次性取出、绑给调用方，body 不再自己加锁。
macro_rules! with_engine {
    ($engine:ident, $body:block) => {{
        // 先取出 Arc<Engine>，随即释放锁，避免与事件线程争用。
        let $engine = match ENGINE.lock() {
            Ok(g) => match g.as_ref() {
                Some(engine) => engine.clone(),
                // 引擎未创建 —— 不是错误，按约定返回 -1
                None => return -1,
            },
            // 锁中毒（有线程持锁时 panic 过）—— 同样返回 -1，绝不 panic
            Err(_) => return -1,
        };
        $body
    }};
}

// ══════════════════════════════════════════════════════════════
// 生命周期
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeCreate(configJson: String, callback: MpvEventCallback): Long`
///
/// 创建引擎。返回 0 表示成功，负数表示失败。
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    callback: JObject,
) -> jlong {
    // 解析配置
    let json: String = match env.get_string(&config_json) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let audio_output = extract_json_string(&json, "audioOutput")
        .unwrap_or_else(|| "audiotrack".to_string());
    let libmpv_path = extract_json_string(&json, "libmpvPath")
        .unwrap_or_else(|| "libmpv.so".to_string());

    // 建立全局回调引用
    let global_callback = match env.new_global_ref(&callback) {
        Ok(g) => g,
        Err(_) => return -2,
    };
    let vm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(_) => return -3,
    };

    let sink: Arc<dyn EventSink> = Arc::new(JniEventSink {
        vm,
        callback: global_callback,
    });
    let props = SharedProps::new();

    let cfg = EngineConfig {
        libmpv_path,
        audio_output,
        audio_enabled: true,
        video_disabled: true,
        sink,
        props,
    };

    match Engine::create(cfg) {
        Ok(engine) => {
            let mut guard = match ENGINE.lock() {
                Ok(g) => g,
                Err(_) => return -4,
            };
            // 替换旧实例（旧的 Arc drop 时自动 shutdown）
            *guard = Some(Arc::new(engine));
            0
        }
        Err(msg) => {
            emit_error_to_log(&msg);
            -10
        }
    }
}

/// `MpvEngine.nativeDestroy(): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let mut guard = match ENGINE.lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    if let Some(engine) = guard.take() {
        engine.shutdown();
    }
    0
}

// ══════════════════════════════════════════════════════════════
// 播放控制
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeLoad(uri: String): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jint {
    let uri: String = match env.get_string(&uri) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    with_engine!(engine, {
        match engine.load(&uri) {
            Ok(_) => 0,
            Err(e) => {
                emit_error_to_log(&e);
                -1
            }
        }
    })
}

/// `MpvEngine.nativeTogglePause(): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeTogglePause(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    with_engine!(engine, {
        match engine.toggle_pause() {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeSetPaused(paused: Boolean): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSetPaused(
    _env: JNIEnv,
    _class: JClass,
    paused: jboolean,
) -> jint {
    with_engine!(engine, {
        match engine.set_paused(paused == JNI_TRUE) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeStop(): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    with_engine!(engine, {
        match engine.stop() {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeSeekAbsolute(seconds: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSeekAbsolute(
    _env: JNIEnv,
    _class: JClass,
    seconds: jdouble,
) -> jint {
    with_engine!(engine, {
        match engine.seek_absolute(seconds) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeSeekRelative(deltaSeconds: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSeekRelative(
    _env: JNIEnv,
    _class: JClass,
    delta: jdouble,
) -> jint {
    with_engine!(engine, {
        match engine.seek_relative(delta) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeSetVolume(volume: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSetVolume(
    _env: JNIEnv,
    _class: JClass,
    volume: jdouble,
) -> jint {
    with_engine!(engine, {
        match engine.set_volume(volume) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

/// `MpvEngine.nativeSetSpeed(speed: Double): Int`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSetSpeed(
    _env: JNIEnv,
    _class: JClass,
    speed: jdouble,
) -> jint {
    with_engine!(engine, {
        match engine.set_speed(speed) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

// ══════════════════════════════════════════════════════════════
// ★ 高频属性读取（对应「用拉不用推」的设计）
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeGetTimePosMs(): Long`
///
/// **这是高频路径**：Kotlin 按渲染帧主动调用。
/// 实现是原子读，**不触碰 mpv 内核，零 JNI 分配**（返回原始 long）。
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeGetTimePosMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => e.props().time_pos_ms() as jlong,
            None => -1,
        },
        Err(_) => -1,
    }
}

/// `MpvEngine.nativeGetDurationMs(): Long`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeGetDurationMs(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => e.props().duration_ms() as jlong,
            None => -1,
        },
        Err(_) => -1,
    }
}

/// `MpvEngine.nativeIsPaused(): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeIsPaused(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => {
                if e.props().is_paused() {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            }
            None => JNI_FALSE,
        },
        Err(_) => JNI_FALSE,
    }
}

/// `MpvEngine.nativeIsIdle(): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeIsIdle(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => {
                if e.props().is_idle() {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            }
            None => JNI_FALSE,
        },
        Err(_) => JNI_FALSE,
    }
}

/// `MpvEngine.nativeGetVolume(): Double`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeGetVolume(
    _env: JNIEnv,
    _class: JClass,
) -> jdouble {
    match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => e.props().volume(),
            None => -1.0,
        },
        Err(_) => -1.0,
    }
}

// ══════════════════════════════════════════════════════════════
// ★ 高解析验证（Demo 阶段的关键接口）
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeGetAudioParams(): String?`
///
/// 返回 JSON：`{"samplerate":96000,"channels":2,"format":"s32","codec":"flac"}`
/// 不可读时返回 null。
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeGetAudioParams(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => match e.audio_params() {
                Ok(p) => format!(
                    r#"{{"samplerate":{},"channels":{},"format":"{}","codec":"{}"}}"#,
                    p.samplerate,
                    p.channels,
                    escape_json(&p.format),
                    escape_json(&p.codec)
                ),
                Err(_) => return std::ptr::null_mut(),
            },
            None => return std::ptr::null_mut(),
        },
        Err(_) => return std::ptr::null_mut(),
    };

    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `MpvEngine.nativeGetPropertyString(name: String): String?`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeGetPropertyString(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name: String = match env.get_string(&name) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let value = match ENGINE.lock() {
        Ok(g) => match g.as_ref() {
            Some(e) => match e.get_property_string(&name) {
                Ok(v) => v,
                Err(_) => return std::ptr::null_mut(),
            },
            None => return std::ptr::null_mut(),
        },
        Err(_) => return std::ptr::null_mut(),
    };
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ══════════════════════════════════════════════════════════════
// ★ EQ / 空间音效（Desktop parity）
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeSetAudioFilters(chain: String): Int`
///
/// `chain` 为 mpv `af` 参数，例如：
/// `"equalizer=f=100:t=q:w=1:g=3,equalizer=f=1000:t=q:w=1:g=-2"`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeSetAudioFilters(
    mut env: JNIEnv,
    _class: JClass,
    chain: JString,
) -> jint {
    let chain: String = match env.get_string(&chain) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    with_engine!(engine, {
        match engine.set_audio_filters(&chain) {
            Ok(_) => 0,
            Err(_) => -1,
        }
    })
}

// ══════════════════════════════════════════════════════════════
// 诊断
// ══════════════════════════════════════════════════════════════

/// `MpvEngine.nativeEngineVersion(): String`
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(yan_engine_core::ENGINE_VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `MpvEngine.nativeLibmpvApiVersion(libPath: String): Long`
///
/// 返回 libmpv 的 API 版本号（编码为 major<<16 | minor）；<0 表示失败。
/// **这是 Demo 阶段验证「.so 能否加载」的第一道探针。**
#[no_mangle]
pub extern "system" fn Java_com_yanmusic_engine_MpvEngine_nativeLibmpvApiVersion(
    mut env: JNIEnv,
    _class: JClass,
    lib_path: JString,
) -> jlong {
    let path: String = match env.get_string(&lib_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    match yan_engine_core::engine::libmpv_api_version(&path) {
        Ok(v) => v as jlong,
        Err(_) => -2,
    }
}

// ── 工具函数 ──

/// 极简 JSON 字符串字段提取（避免为一行解析引入 serde 依赖）。
fn extract_json_string(json: &str, key: &str) -> Option<String> {
    let pattern = format!("\"{key}\"");
    let start = json.find(&pattern)? + pattern.len();
    let rest = &json[start..];
    let colon = rest.find(':')? + 1;
    let after = rest[colon..].trim_start();
    let after = after.strip_prefix('"')?;
    let end = after.find('"')?;
    Some(after[..end].to_string())
}

fn escape_json(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// 把错误打到 Android logcat（便于 Demo 阶段排查）。
fn emit_error_to_log(msg: &str) {
    // 用 Android 的 __android_log_print 需要额外依赖；
    // Demo 阶段先打到 stderr（logcat 会捕获 native stderr）。
    eprintln!("[yan-engine-jni] ERROR: {msg}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_extraction_works() {
        let json = r#"{"audioOutput":"aaudio","libmpvPath":"libmpv.so"}"#;
        assert_eq!(
            extract_json_string(json, "audioOutput"),
            Some("aaudio".to_string())
        );
        assert_eq!(
            extract_json_string(json, "libmpvPath"),
            Some("libmpv.so".to_string())
        );
        assert_eq!(extract_json_string(json, "missing"), None);
    }

    #[test]
    fn json_extraction_handles_whitespace() {
        let json = "{ \"audioOutput\" :  \"audiotrack\" }";
        assert_eq!(
            extract_json_string(json, "audioOutput"),
            Some("audiotrack".to_string())
        );
    }

    #[test]
    fn json_escaping() {
        assert_eq!(escape_json(r#"a"b\c"#), r#"a\"b\\c"#);
    }

    #[test]
    fn jni_signature_is_correct() {
        // (Int, Double, Long, String) → void
        assert_eq!(JniEventSink::SIG, "(IDJLjava/lang/String;)V");
        assert_eq!(JniEventSink::METHOD, "onEvent");
    }

    /// 守卫：Rust 侧必须定义**全部 20 个** JNI 符号，且名字与 Kotlin 严格对应。
    ///
    /// ## 为什么重写（2026-10-06）
    ///
    /// 旧版本只硬编码了 5 个名字，然后断言
    /// `assert!(name.starts_with("Java_com_yanmusic_engine_MpvEngine_native"))`
    /// —— 这是**恒真断言**：那 5 个字符串字面量当然都以前缀开头，
    /// 它**与 Rust 实际定义了哪些函数毫无关系**。改坏第 6~20 个符号，
    /// 该测试仍然全绿。属于「标识符存在 ≠ 生效」的又一变体。
    ///
    /// ## 现在的做法
    ///
    /// 直接从源码文本里抽出**实际定义的** `pub extern "system" fn` 符号名，
    /// 与一份**完整的 20 项期望清单**做集合比较（双向）。
    /// 少定义、多定义、拼错，任何一种都会红。
    #[test]
    fn jni_symbols_match_kotlin_declaration_exactly() {
        let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"));

        // 1) 抽取 Rust 实际定义的 JNI 符号（只认函数定义行，避免命中文档注释）
        let mut actual: Vec<&str> = src
            .lines()
            .filter_map(|l| {
                let t = l.trim_start();
                t.strip_prefix("pub extern \"system\" fn ")
            })
            .filter_map(|rest| {
                let sym = rest.split('(').next()?.trim();
                sym.starts_with("Java_com_yanmusic_engine_MpvEngine_").then_some(sym)
            })
            .collect();
        actual.sort_unstable();
        actual.dedup();

        // 2) 期望清单 —— 必须与 Kotlin `MpvEngine.kt` 的 external fun 一一对应。
        //    ⚠️ 增删任何 JNI 方法时，**这里和 Kotlin 一起改**。
        const EXPECTED: [&str; 20] = [
            "Java_com_yanmusic_engine_MpvEngine_nativeCreate",
            "Java_com_yanmusic_engine_MpvEngine_nativeDestroy",
            "Java_com_yanmusic_engine_MpvEngine_nativeLoad",
            "Java_com_yanmusic_engine_MpvEngine_nativeTogglePause",
            "Java_com_yanmusic_engine_MpvEngine_nativeSetPaused",
            "Java_com_yanmusic_engine_MpvEngine_nativeStop",
            "Java_com_yanmusic_engine_MpvEngine_nativeSeekAbsolute",
            "Java_com_yanmusic_engine_MpvEngine_nativeSeekRelative",
            "Java_com_yanmusic_engine_MpvEngine_nativeSetVolume",
            "Java_com_yanmusic_engine_MpvEngine_nativeSetSpeed",
            "Java_com_yanmusic_engine_MpvEngine_nativeGetTimePosMs",
            "Java_com_yanmusic_engine_MpvEngine_nativeGetDurationMs",
            "Java_com_yanmusic_engine_MpvEngine_nativeIsPaused",
            "Java_com_yanmusic_engine_MpvEngine_nativeIsIdle",
            "Java_com_yanmusic_engine_MpvEngine_nativeGetVolume",
            "Java_com_yanmusic_engine_MpvEngine_nativeGetAudioParams",
            "Java_com_yanmusic_engine_MpvEngine_nativeGetPropertyString",
            "Java_com_yanmusic_engine_MpvEngine_nativeSetAudioFilters",
            "Java_com_yanmusic_engine_MpvEngine_nativeEngineVersion",
            "Java_com_yanmusic_engine_MpvEngine_nativeLibmpvApiVersion",
        ];
        let mut expected: Vec<&str> = EXPECTED.to_vec();
        expected.sort_unstable();

        // 3) 双向差集
        let missing: Vec<&&str> = expected.iter().filter(|e| !actual.contains(*e)).collect();
        let extra: Vec<&&str> = actual.iter().filter(|a| !expected.contains(*a)).collect();

        assert!(
            missing.is_empty(),
            "Rust 缺少以下 JNI 符号定义（Kotlin 已声明）：{missing:#?}"
        );
        assert!(
            extra.is_empty(),
            "Rust 定义了 Kotlin 未声明的 JNI 符号（Kotlin 侧会 UnsatisfiedLinkError）：{extra:#?}"
        );
        assert_eq!(
            actual.len(),
            20,
            "JNI 符号总数应为 20，实际 {}：{actual:#?}",
            actual.len()
        );
    }

    /// 守卫：`with_engine!` 的 **body 里不得再自行加锁 / unwrap**。
    ///
    /// ## 为什么需要这条守卫
    ///
    /// 2026-10-04 发现：宏内原本只做「存在性检查」，body 里却写
    /// `ENGINE.lock().unwrap().as_ref().unwrap().xxx()`。这会让
    /// ① 锁中毒（`PoisonError`）从「返回 -1」变成 panic；
    /// ② 宏释放锁后若 `nativeDestroy` 抢先置 `None`，`.as_ref().unwrap()` 会 panic。
    ///
    /// 而 **panic 跨越 `extern "system"` 是 UB** —— 播放器会硬崩而非返回错误码。
    /// 修复后 engine 由宏一次性 clone 并绑给 body，body 不再加锁。
    ///
    /// ## 实现说明
    ///
    /// 这是**源码级守卫**（模块不能在自己内部以运行时方式加载自身）。
    /// 为避免误伤：**只断言「宏展开体内部」的文本**——
    /// 先定位 `macro_rules! with_engine`，截到其后第一个 `\n}` 为止。
    ///
    /// ⚠️ 不要退化成「整文件 contains」——那会被本测试自身的文档字符串、
    ///    以及文件里 8 处本来合法的直接 `match ENGINE.lock()` 读属性污染。
    #[test]
    fn with_engine_body_must_not_relock() {
        let src = include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"));

        // 1) 定位宏定义体（到第一个行首 `}` 结束）
        //
        // ⚠️ 注意起点选取：`macro_rules! with_engine` **之前**有长篇文档注释，
        //    里面举了反例 `ENGINE.lock().unwrap().as_ref().unwrap()`。
        //    所以必须从 `macro_rules!` 本身开始截，不能从更早的地方截，
        //    否则守卫会被自己的文档污染（第一版就栽在这）。
        let macro_start = src
            .find("macro_rules! with_engine")
            .expect("找不到 macro_rules! with_engine 定义");
        let rest = &src[macro_start..];
        let body_end = rest
            .find("\n}")
            .expect("宏定义体缺少结束花括号");
        let macro_body = &rest[..body_end];

        // 2) 宏体**必须**锁定 ENGINE（这是它的职责），但**不得 unwrap** ——
        //    中毒必须走 `match ... Err(_) => return -1`。
        assert!(
            macro_body.contains("ENGINE.lock()"),
            "with_engine! 宏体应自行锁定 ENGINE（若已改为不锁，请更新本守卫）"
        );
        assert!(
            !macro_body.contains(".unwrap()"),
            "with_engine! 宏体内不得出现 .unwrap() —— 锁中毒必须返回 -1 而非 panic。\n宏体：\n{macro_body}"
        );

        // 3) ★ 核心断言：所有 with_engine! 调用点的**实参块内**不得出现
        //    `ENGINE.lock()` 或 `.as_ref().unwrap()`
        //
        //    做法：逐个扫描 `with_engine!(` 出现处，向后配对花括号取实参块。
        //
        //    ⚠️ 必须先剔除注释行！本宏的文档注释里就有一个示例
        //    `/// with_engine!(engine, {` —— 若把它也算进来，
        //    花括号配对会被 `///` 前缀与跨行注释搞乱（第一版即栽在此）。
        //
        //    ⚠️ 还要**截到测试模块之前**：本守卫函数自身含有字符串字面量
        //    `"with_engine!("`，若不截断会扫到自己，导致自指死循环式误判
        //    （第二版即栽在此）。
        let prod_src = match src.find("#[cfg(test)]") {
            Some(i) => &src[..i],
            None => src,
        };
        let scan_src: String = prod_src
            .lines()
            .filter(|l| {
                let t = l.trim_start();
                !(t.starts_with("///") || t.starts_with("//!"))
            })
            .collect::<Vec<_>>()
            .join("\n");
        let src = scan_src.as_str();

        let mut search_from = 0usize;
        let mut checked = 0usize;
        while let Some(pos) = src[search_from..].find("with_engine!(") {
            let abs = search_from + pos + "with_engine!(".len();
            // 跳过 `engine, {` 之类的头，找到块起始 `{`
            let after = &src[abs..];
            let brace_rel = after.find('{').expect("with_engine! 调用缺少块实参");
            let block_start = abs + brace_rel;

            // 配对花括号（源码里字符串字面量极少含花括号，此处足够稳健）
            let mut depth = 0i32;
            let mut end = None;
            for (i, ch) in src[block_start..].char_indices() {
                match ch {
                    '{' => depth += 1,
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            end = Some(block_start + i);
                            break;
                        }
                    }
                    _ => {}
                }
            }
            let end = end.expect("with_engine! 块花括号不配对");
            let block = &src[block_start..end];

            assert!(
                !block.contains("ENGINE.lock()"),
                "with_engine! 的块内不得再锁定 ENGINE（绕过中毒检查）。\n块内容：\n{block}"
            );
            assert!(
                !block.contains(".as_ref().unwrap()"),
                "with_engine! 的块内不得出现 .as_ref().unwrap()（TOCTOU panic 风险）。\n块内容：\n{block}"
            );

            checked += 1;
            search_from = end + 1;
        }

        // 4) 鉴别力：确保真的扫到了调用点（否则断言形同虚设）
        assert!(
            checked >= 8,
            "预期至少扫到 8 个 with_engine! 调用点，实际 {checked} —— 扫描逻辑可能失效"
        );
    }
}
