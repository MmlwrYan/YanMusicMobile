//! YanMusic 跨平台音频引擎核心。
//!
//! **平台无关**：本 crate 不 import `jni` / `napi` / `electron` 任何东西。
//! 平台差异全部通过 [`bridge::EventSink`] 抽象出去。
//!
//! 组件：
//! - [`bridge`]  —— 回调抽象 + 高频属性共享单元（跨平台接缝）
//! - [`mpv_ffi`] —— libmpv 的 C ABI 绑定（`libloading` 运行时加载）
//! - [`engine`]  —— 引擎生命周期与命令封装
//! - [`events`]  —— 事件轮询线程

pub mod bridge;
pub mod engine;
pub mod events;
pub mod mpv_ffi;

pub use bridge::{EventSink, EventType, NullEventSink, SharedProps};
pub use engine::{Engine, EngineConfig};

/// 引擎版本（用于 Kotlin/Swift 侧做兼容性断言）
pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    /// 测试用的 sink：把事件记下来
    struct RecordingSink {
        events: Mutex<Vec<(EventType, f64, i64, String)>>,
    }

    impl RecordingSink {
        fn new() -> Arc<Self> {
            Arc::new(Self {
                events: Mutex::new(Vec::new()),
            })
        }
        fn count(&self) -> usize {
            self.events.lock().unwrap().len()
        }
    }

    impl EventSink for RecordingSink {
        fn emit(&self, event: EventType, arg_f64: f64, arg_i64: i64, text: &str) {
            self.events
                .lock()
                .unwrap()
                .push((event, arg_f64, arg_i64, text.to_string()));
        }
    }

    #[test]
    fn event_type_encoding_is_stable() {
        // 跨语言契约：这些数值 Kotlin/Swift 侧硬编码依赖，不得改动
        assert_eq!(EventType::TimeUpdate.as_i32(), 1);
        assert_eq!(EventType::DurationChange.as_i32(), 2);
        assert_eq!(EventType::StateChange.as_i32(), 3);
        assert_eq!(EventType::PlaybackEnd.as_i32(), 4);
        assert_eq!(EventType::FileLoaded.as_i32(), 5);
        assert_eq!(EventType::Idle.as_i32(), 6);
        assert_eq!(EventType::Error.as_i32(), 7);
        assert_eq!(EventType::LogMessage.as_i32(), 8);
    }

    #[test]
    fn shared_props_roundtrip_without_precision_loss() {
        let p = SharedProps::new();

        p.set_time_pos(123.456);
        assert_eq!(p.time_pos_ms(), 123456);

        // 毫秒精度：99.999 秒应精确到 99999 ms（不是 99998/100000）
        p.set_time_pos(99.999);
        assert_eq!(p.time_pos_ms(), 99999);

        p.set_duration(3600.0);
        assert_eq!(p.duration_ms(), 3_600_000);

        p.set_volume(73.5);
        assert!((p.volume() - 73.5).abs() < 1e-9);

        p.set_speed(1.25);
        assert!((p.speed() - 1.25).abs() < 1e-9);

        p.set_paused(true);
        assert!(p.is_paused());
        p.set_paused(false);
        assert!(!p.is_paused());

        p.set_idle(false);
        assert!(!p.is_idle());
    }

    #[test]
    fn shared_props_decimal_rounds_correctly() {
        let p = SharedProps::new();
        // 0.0005 秒 = 0.5 ms，四舍五入应得 1 ms（验证 round 而非 trunc）
        p.set_time_pos(0.0005);
        assert_eq!(p.time_pos_ms(), 1);

        // 0.0004 秒 = 0.4 ms → 0 ms
        p.set_time_pos(0.0004);
        assert_eq!(p.time_pos_ms(), 0);
    }

    #[test]
    fn event_sink_is_callable_from_multiple_threads() {
        let sink = RecordingSink::new();
        let mut handles = Vec::new();
        for _ in 0..4 {
            let s: Arc<dyn EventSink> = sink.clone();
            handles.push(std::thread::spawn(move || {
                for _ in 0..250 {
                    s.emit(EventType::TimeUpdate, 1.0, 0, "");
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(sink.count(), 1000);
    }

    #[test]
    fn null_sink_accepts_everything() {
        let s = NullEventSink;
        s.emit(EventType::Error, 0.0, 0, "boom");
        // 不 panic 即通过
    }

    #[test]
    fn engine_version_is_exposed() {
        assert!(!ENGINE_VERSION.is_empty());
        assert_eq!(ENGINE_VERSION, "0.1.0");
    }
}
