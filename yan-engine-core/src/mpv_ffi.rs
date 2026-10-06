//! libmpv 的 C ABI 绑定 —— **运行时动态加载**。
//!
//! 沿用桌面版 `native/yan-mpv-player/src/mpv_ffi.rs` 的设计：
//! 用 `libloading` 在运行时 `dlopen`，而不是编译期链接。
//!
//! # 为什么动态加载（关键跨平台收益）
//!
//! | 收益 | 说明 |
//! |---|---|
//! | 消除编译期链接差异 | Android 用 `System.loadLibrary` 后路径已知；iOS 用 `dlopen` 或静态链接 |
//! | FFI 声明纯 C ABI | 两个平台都能用，无需 `#[cfg]` 分叉 |
//! | 加载失败可优雅降级 | 拿不到 libmpv 时返回 Err，而不是加载 App 就崩 |
//!
//! # 平台注意
//!
//! - **Android**：`.so` 放在 `jniLibs/<abi>/`，由系统加载器自动解析依赖；
//!   传给 [`MpvLib::load`] 的路径可用 `libmpv.so`（链接器会在搜索路径里找）。
//! - **iOS**：静态链接时无 `dlopen` 需求，直接用符号地址；本模块的
//!   `load` 路径仅用于动态场景。

#![allow(non_snake_case, non_camel_case_types, dead_code)]

use std::os::raw::{c_char, c_int, c_void};

/// libmpv 不透明句柄
pub enum MpvHandle {}
/// libmpv 事件（不透明，字段按需读取）
pub enum MpvEvent {}
/// libmpv 属性事件
pub enum MpvEventProperty {}
/// libmpv 日志事件
pub enum MpvEventLogMessage {}

// ── 事件 ID（来自 mpv/client.h，数值稳定） ──
pub const MPV_EVENT_NONE: c_int = 0;
pub const MPV_EVENT_SHUTDOWN: c_int = 1;
pub const MPV_EVENT_LOG_MESSAGE: c_int = 2;
pub const MPV_EVENT_END_FILE: c_int = 7;
pub const MPV_EVENT_FILE_LOADED: c_int = 8;
pub const MPV_EVENT_IDLE: c_int = 11;
pub const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

// ── 属性格式 ──
pub const MPV_FORMAT_NONE: c_int = 0;
pub const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_FORMAT_FLAG: c_int = 3;
pub const MPV_FORMAT_INT64: c_int = 4;
pub const MPV_FORMAT_DOUBLE: c_int = 5;

// ── end-file 原因 ──
pub const MPV_END_FILE_REASON_EOF: c_int = 0;
pub const MPV_END_FILE_REASON_STOP: c_int = 2;
pub const MPV_END_FILE_REASON_ERROR: c_int = 4;

/// 动态加载的 libmpv。
///
/// ⚠️ 字段顺序重要：`_lib` 必须先于所有 `Symbol` 声明，
/// 保证 drop 时 `Library` 最后释放（Symbol 借用自它）。
pub struct MpvLib {
    _lib: libloading::Library,

    pub mpv_create: unsafe extern "C" fn() -> *mut MpvHandle,
    pub mpv_initialize: unsafe extern "C" fn(*mut MpvHandle) -> c_int,
    pub mpv_terminate_destroy: unsafe extern "C" fn(*mut MpvHandle),
    pub mpv_command: unsafe extern "C" fn(*mut MpvHandle, *const *const c_char) -> c_int,
    pub mpv_command_string: unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> c_int,
    pub mpv_set_property: unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int,
    pub mpv_set_property_string: unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int,
    pub mpv_get_property: unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int,
    pub mpv_get_property_string: unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> *mut c_char,
    pub mpv_set_option_string: unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int,
    pub mpv_request_log_messages: unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> c_int,
    pub mpv_observe_property: unsafe extern "C" fn(*mut MpvHandle, u64, *const c_char, c_int) -> c_int,
    pub mpv_wait_event: unsafe extern "C" fn(*mut MpvHandle, f64) -> *mut MpvEvent,
    pub mpv_free: unsafe extern "C" fn(*mut c_void),
    pub mpv_wakeup: unsafe extern "C" fn(*mut MpvHandle),
    pub mpv_error_string: unsafe extern "C" fn(c_int) -> *const c_char,
    pub mpv_client_api_version: unsafe extern "C" fn() -> u64,
}

impl MpvLib {
    /// 运行时加载 libmpv。
    ///
    /// # 参数
    /// - `lib_path`：动态库路径。
    ///   - Android 传 `"libmpv.so"`（加载器自动在 `jniLibs` 搜索）。
    ///   - 桌面/测试可传绝对路径。
    ///
    /// # Safety
    /// 调用方需保证对应路径下是可信的 libmpv 构建。
    pub unsafe fn load(lib_path: &str) -> Result<Self, String> {
        #[cfg(target_os = "android")]
        let lib: libloading::Library = {
            // Android 上优先让系统加载器处理（已 System.loadLibrary 过的会命中缓存）
            libloading::Library::new(lib_path)
                .map_err(|e| format!("failed to load libmpv ({lib_path}): {e}"))?
        };

        #[cfg(target_os = "linux")]
        let lib: libloading::Library = {
            use libloading::os::unix::Library as UnixLibrary;
            // RTLD_NOW = 0x2
            // 不用 RTLD_DEEPBIND：与新版 glibc + Electron PartitionAlloc 冲突会崩。
            // 符号冲突由外部 LD_LIBRARY_PATH 在进程启动前解决（桌面版既有约定）。
            UnixLibrary::open(Some(lib_path), 0x2)
                .map_err(|e| format!("failed to load libmpv ({lib_path}): {e}"))?
                .into()
        };

        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        let lib: libloading::Library = libloading::Library::new(lib_path)
            .map_err(|e| format!("failed to load libmpv ({lib_path}): {e}"))?;

        // Symbol 借用自 lib，两者存在同一结构体里；
        // Library 的字段声明在 Symbol 之前 → drop 顺序安全。
        macro_rules! load_fn {
            ($name:ident, $ty:ty) => {{
                let sym: libloading::Symbol<$ty> = lib
                    .get(stringify!($name).as_bytes())
                    .map_err(|e| format!("failed to load symbol {}: {e}", stringify!($name)))?;
                std::mem::transmute::<libloading::Symbol<$ty>, $ty>(sym)
            }};
        }

        Ok(Self {
            mpv_create: load_fn!(mpv_create, unsafe extern "C" fn() -> *mut MpvHandle),
            mpv_initialize: load_fn!(mpv_initialize, unsafe extern "C" fn(*mut MpvHandle) -> c_int),
            mpv_terminate_destroy: load_fn!(mpv_terminate_destroy, unsafe extern "C" fn(*mut MpvHandle)),
            mpv_command: load_fn!(mpv_command, unsafe extern "C" fn(*mut MpvHandle, *const *const c_char) -> c_int),
            mpv_command_string: load_fn!(mpv_command_string, unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> c_int),
            mpv_set_property: load_fn!(mpv_set_property, unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int),
            mpv_set_property_string: load_fn!(mpv_set_property_string, unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int),
            mpv_get_property: load_fn!(mpv_get_property, unsafe extern "C" fn(*mut MpvHandle, *const c_char, c_int, *mut c_void) -> c_int),
            mpv_get_property_string: load_fn!(mpv_get_property_string, unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> *mut c_char),
            mpv_set_option_string: load_fn!(mpv_set_option_string, unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int),
            mpv_request_log_messages: load_fn!(mpv_request_log_messages, unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> c_int),
            mpv_observe_property: load_fn!(mpv_observe_property, unsafe extern "C" fn(*mut MpvHandle, u64, *const c_char, c_int) -> c_int),
            mpv_wait_event: load_fn!(mpv_wait_event, unsafe extern "C" fn(*mut MpvHandle, f64) -> *mut MpvEvent),
            mpv_free: load_fn!(mpv_free, unsafe extern "C" fn(*mut c_void)),
            mpv_wakeup: load_fn!(mpv_wakeup, unsafe extern "C" fn(*mut MpvHandle)),
            mpv_error_string: load_fn!(mpv_error_string, unsafe extern "C" fn(c_int) -> *const c_char),
            mpv_client_api_version: load_fn!(mpv_client_api_version, unsafe extern "C" fn() -> u64),
            _lib: lib,
        })
    }
}

/// 判断 libmpv 是否可用（用于测试与运行时能力探测）。
///
/// 不 panic —— 加载失败返回 `false`。
pub fn is_libmpv_available(path: &str) -> bool {
    unsafe { MpvLib::load(path).is_ok() }
}

/// 读取 `mpv_event` 的 event_id（裸指针 → 字段）。
///
/// 由于 `MpvEvent` 是不透明类型，这里按 C 结构布局读取头部字段。
/// mpv 的 `mpv_event` 结构首字段即为 `event_id`（int）。
///
/// # Safety
/// `ev` 必须是 `mpv_wait_event` 返回的有效指针。
pub unsafe fn event_id(ev: *mut MpvEvent) -> c_int {
    if ev.is_null() {
        return MPV_EVENT_NONE;
    }
    // mpv_event { mpv_event_id event_id; int error; ... }
    *(ev as *const c_int)
}

/// 读取 `mpv_event.data` 指针（第 3 个字段，按指针宽度偏移）。
///
/// # Safety
/// 同 [`event_id`]。
pub unsafe fn event_data(ev: *mut MpvEvent) -> *mut c_void {
    if ev.is_null() {
        return std::ptr::null_mut();
    }
    // 布局：event_id(int,4) + error(int,4) + data(ptr) → 偏移 8（64 位含对齐）
    let base = ev as *const u8;
    let ptr = base.add(std::mem::size_of::<usize>().max(8)) as *const *mut c_void;
    *ptr
}

/// 读取 `mpv_event_property` 的名称（首字段，`const char*`）。
///
/// # Safety
/// `prop` 必须指向有效的 `mpv_event_property`。
pub unsafe fn property_name(prop: *mut MpvEventProperty) -> *const c_char {
    if prop.is_null() {
        return std::ptr::null();
    }
    *(prop as *const *const c_char)
}

/// 读取 `mpv_event_property` 的 format（`name` 之后的 int 字段）。
///
/// # Safety
/// 同 [`property_name`]。
pub unsafe fn property_format(prop: *mut MpvEventProperty) -> c_int {
    if prop.is_null() {
        return MPV_FORMAT_NONE;
    }
    let base = prop as *const u8;
    // mpv_event_property { const char *name; mpv_format format; void *data; }
    let fmt_ptr = base.add(std::mem::size_of::<usize>()) as *const c_int;
    *fmt_ptr
}

/// 读取 `mpv_event_property` 的 data 指针（第 3 字段）。
///
/// # Safety
/// 同 [`property_name`]。
pub unsafe fn property_data(prop: *mut MpvEventProperty) -> *mut c_void {
    if prop.is_null() {
        return std::ptr::null_mut();
    }
    let base = prop as *const u8;
    let data_ptr = base.add(std::mem::size_of::<usize>() + 8) as *const *mut c_void;
    *data_ptr
}
