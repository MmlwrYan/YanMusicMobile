//! # EventSink —— 平台回调抽象
//!
//! 这是整个跨平台设计的**唯一接缝**。
//!
//! `yan-engine-core` 绝不 import `jni` 或 `napi` —— 它只知道这个 trait。
//! 各平台绑定层（`yan-engine-jni` / `yan-engine-cabi`）各自实现它。
//!
//! ```text
//!   yan-engine-core  ──依赖──>  trait EventSink
//!                                    △
//!                      ┌─────────────┴─────────────┐
//!             JniEventSink                  CCallbackSink
//!             (Android)                     (iOS)
//! ```

use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;

/// 事件类型枚举 —— 与 Kotlin / Swift 侧约定一致的整数编码。
///
/// ⚠️ **这是跨语言契约，数值一旦发布不得随意改动。**
/// 新增事件只能追加新编号，不得复用已删除的编号。
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventType {
    /// 播放位置更新（秒）。arg_f64 = time-pos
    TimeUpdate = 1,
    /// 时长确定（秒）。arg_f64 = duration
    DurationChange = 2,
    /// 暂停状态变化。arg_i64 = 1(暂停) / 0(播放)
    StateChange = 3,
    /// 播放结束。text = "eof" | "stop" | "error"
    PlaybackEnd = 4,
    /// 文件加载完成。text = path
    FileLoaded = 5,
    /// 进入空闲
    Idle = 6,
    /// 错误。text = 描述
    Error = 7,
    /// 日志。text = 内容
    LogMessage = 8,
}

impl EventType {
    pub fn as_i32(self) -> i32 {
        self as i32
    }
}

/// 平台回调接口。
///
/// # 线程约定（**实现者必须遵守**）
///
/// - `emit` **在 Rust 事件线程被调用，不是主线程**。
///   实现者若需触碰 UI，**必须自行切回主线程**（Kotlin: `Handler.post` /
///   Swift: `DispatchQueue.main.async`）。
/// - `emit` **必须快速返回**。它是 NonBlocking 语义 —— 实现者内部应只做
///   「投递到消息队列」这类 O(1) 操作，不得在 `emit` 内做耗时工作，
///   否则会阻塞 mpv 事件循环，导致播放卡顿。
/// - `emit` **可能被多线程并发调用**（当前实现只有单个事件线程，
///   但契约上按并发安全要求实现）。
pub trait EventSink: Send + Sync {
    /// 投递一个事件。
    ///
    /// 参数刻意只用**原始类型 + &str**，避免跨语言边界构造对象：
    /// - `event`: 事件类型（见 `EventType`）
    /// - `arg_f64`: 浮点载荷（time-pos / duration）
    /// - `arg_i64`: 整数载荷（布尔用 0/1，或整数参数）
    /// - `text`: 字符串载荷（路径、错误、日志）；无则传空串
    fn emit(&self, event: EventType, arg_f64: f64, arg_i64: i64, text: &str);
}

/// 一个什么都不做的 sink —— 用于测试与「只读属性、不关心事件」的场景。
pub struct NullEventSink;

impl EventSink for NullEventSink {
    fn emit(&self, _event: EventType, _arg_f64: f64, _arg_i64: i64, _text: &str) {}
}

/// 高频属性的共享单元。
///
/// **设计要点（回答「如何避免高频 JNI 调用卡顿」）**：
/// `time-pos` 这类属性变化频率高，但 UI 只需要按渲染帧读取。
/// 若每次变化都跨语言回调，会造成数百次/秒的边界穿越。
///
/// 因此：**写入侧是 Rust 内部的无锁原子写（零跨语言成本），
/// 读取侧由 Kotlin/Swift 按需主动拉取（一次调用拿一个 long）。**
///
/// 这比「每次变化都推送」少一个数量级的跨界调用。
#[derive(Debug)]
pub struct SharedProps {
    /// 当前播放位置（毫秒）。用 i64 避免浮点跨语言精度问题。
    pub time_pos_ms: AtomicI64,
    /// 总时长（毫秒）。
    pub duration_ms: AtomicI64,
    /// 是否暂停。
    pub paused: AtomicBool,
    /// 音量（0-100，放大 100 倍存整数以复用原子类型）。
    pub volume_x100: AtomicI64,
    /// 播放速率（放大 1000 倍）。
    pub speed_x1000: AtomicI64,
    /// 是否空闲。
    pub idle: AtomicBool,
}

impl Default for SharedProps {
    fn default() -> Self {
        Self {
            time_pos_ms: AtomicI64::new(0),
            duration_ms: AtomicI64::new(0),
            paused: AtomicBool::new(true),
            volume_x100: AtomicI64::new(10000), // 100.00
            speed_x1000: AtomicI64::new(1000),  // 1.000
            idle: AtomicBool::new(true),
        }
    }
}

impl SharedProps {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    #[inline]
    pub fn set_time_pos(&self, seconds: f64) {
        self.time_pos_ms
            .store((seconds * 1000.0).round() as i64, Ordering::Relaxed);
    }

    #[inline]
    pub fn time_pos_ms(&self) -> i64 {
        self.time_pos_ms.load(Ordering::Relaxed)
    }

    #[inline]
    pub fn set_duration(&self, seconds: f64) {
        self.duration_ms
            .store((seconds * 1000.0).round() as i64, Ordering::Relaxed);
    }

    #[inline]
    pub fn duration_ms(&self) -> i64 {
        self.duration_ms.load(Ordering::Relaxed)
    }

    #[inline]
    pub fn set_paused(&self, paused: bool) {
        self.paused.store(paused, Ordering::Relaxed);
    }

    #[inline]
    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Relaxed)
    }

    #[inline]
    pub fn set_volume(&self, volume: f64) {
        self.volume_x100
            .store((volume * 100.0).round() as i64, Ordering::Relaxed);
    }

    #[inline]
    pub fn volume(&self) -> f64 {
        self.volume_x100.load(Ordering::Relaxed) as f64 / 100.0
    }

    #[inline]
    pub fn set_speed(&self, speed: f64) {
        self.speed_x1000
            .store((speed * 1000.0).round() as i64, Ordering::Relaxed);
    }

    #[inline]
    pub fn speed(&self) -> f64 {
        self.speed_x1000.load(Ordering::Relaxed) as f64 / 1000.0
    }

    #[inline]
    pub fn set_idle(&self, idle: bool) {
        self.idle.store(idle, Ordering::Relaxed);
    }

    #[inline]
    pub fn is_idle(&self) -> bool {
        self.idle.load(Ordering::Relaxed)
    }
}
