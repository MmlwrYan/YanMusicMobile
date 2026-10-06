package com.yanmusic.engine

/**
 * YanMusic 引擎的 Kotlin 门面。
 *
 * ## 与 Rust 侧的契约
 *
 * 本类的方法名**必须**与 `android/yan-engine-jni/src/lib.rs` 里的
 * `Java_com_yanmusic_engine_MpvEngine_nativeXxx` 符号严格对应。
 *
 * ⚠️ **改动本类的包名 / 类名 / 任一 native 方法名，都必须同步改 Rust 侧**，
 * 否则运行时报 `UnsatisfiedLinkError`。Rust 侧有单测断言命名前缀
 * （`engine_symbol_names_match_kotlin_convention`），改坏了会先在 CI 红。
 *
 * ## 线程模型（重要）
 *
 * - **所有 public 方法都是线程安全的**（内部走 Rust 的原子量 / 互斥锁）。
 * - **事件回调 `onEvent` 在 Rust 事件线程被调用，不是主线程**。
 *   触碰 UI 前**必须**自行切回主线程（见 [MpvEventCallback] 文档）。
 * - **进度 / 时长用「拉」不用「推」**：高频属性不要依赖事件，
 *   而是按渲染帧主动调 [getTimePosMs] / [getDurationMs]。
 */
object MpvEngine {

    /** 引擎是否已成功创建。 */
    @Volatile
    var isInitialized: Boolean = false
        private set

    /** 最近一次失败的原因（用于 Demo 排障与 UI 提示）。 */
    @Volatile
    var lastError: String? = null
        private set

    // ────────────────────────────────────────────────────────────
    // native 方法声明
    // 名称必须与 Rust 的 Java_com_yanmusic_engine_MpvEngine_* 对应
    // ────────────────────────────────────────────────────────────

    private external fun nativeCreate(configJson: String, callback: MpvEventCallback): Long
    private external fun nativeDestroy(): Int

    private external fun nativeLoad(uri: String): Int
    private external fun nativeTogglePause(): Int
    private external fun nativeSetPaused(paused: Boolean): Int
    private external fun nativeStop(): Int
    private external fun nativeSeekAbsolute(seconds: Double): Int
    private external fun nativeSeekRelative(deltaSeconds: Double): Int
    private external fun nativeSetVolume(volume: Double): Int
    private external fun nativeSetSpeed(speed: Double): Int

    private external fun nativeGetTimePosMs(): Long
    private external fun nativeGetDurationMs(): Long
    private external fun nativeIsPaused(): Boolean
    private external fun nativeIsIdle(): Boolean
    private external fun nativeGetVolume(): Double

    private external fun nativeGetAudioParams(): String?
    private external fun nativeGetPropertyString(name: String): String?

    private external fun nativeSetAudioFilters(chain: String): Int

    private external fun nativeEngineVersion(): String
    private external fun nativeLibmpvApiVersion(libPath: String): Long

    // ────────────────────────────────────────────────────────────
    // 加载原生库
    // ────────────────────────────────────────────────────────────

    /**
     * 加载 native 库。
     *
     * 顺序很重要：先加载 libmpv 及其依赖，再加载我们的桥接库。
     * 虽然 Android 的加载器会自动解析 `DT_NEEDED`，但显式加载
     * libmpv 能让「依赖缺失」的错误更早、更清晰地暴露出来
     * （否则报错会是桥接库加载失败的模糊信息）。
     *
     * @return 成功返回 null，失败返回错误描述
     */
    fun loadNativeLibraries(): String? {
        return try {
            // 1. libmpv 本体（其依赖 libavcodec 等由加载器自动拉取）
            System.loadLibrary("mpv")
            // 2. 我们的 Rust 桥接层
            System.loadLibrary("yan_engine_jni")
            null
        } catch (t: UnsatisfiedLinkError) {
            val msg = buildString {
                append("原生库加载失败：").append(t.message)
                append("\n排查：")
                append("\n  1) libmpv.so 及依赖（libavcodec/libavformat/libavutil/libswresample…）")
                append("是否都在 jniLibs/arm64-v8a/ 下？")
                append("\n  2) 是否只放了 libmpv.so 而漏了它的依赖？")
                append("\n  3) 架构是否与该设备的 ABI 匹配？")
            }
            msg
        }
    }

    // ────────────────────────────────────────────────────────────
    // 生命周期
    // ────────────────────────────────────────────────────────────

    /**
     * 创建引擎。
     *
     * @param audioOutput Android 上推荐 `"aaudio"`（低延迟，API 26+）
     *                    或 `"audiotrack"`（兼容性最好）
     * @param libmpvPath  通常就用 `"libmpv.so"`
     * @param callback    事件回调（在 Rust 事件线程被调用）
     * @return 成功返回 true
     */
    fun create(
        callback: MpvEventCallback,
        audioOutput: String = "aaudio",
        libmpvPath: String = "libmpv.so",
    ): Boolean {
        val config = """{"audioOutput":"$audioOutput","libmpvPath":"$libmpvPath"}"""
        val rc = nativeCreate(config, callback)
        if (rc != 0L) {
            lastError = "nativeCreate 返回 $rc（0 才是成功）"
            isInitialized = false
            return false
        }
        isInitialized = true
        lastError = null
        return true
    }

    /** 销毁引擎并释放 native 资源。 */
    fun destroy() {
        if (!isInitialized) return
        nativeDestroy()
        isInitialized = false
    }

    // ────────────────────────────────────────────────────────────
    // 播放控制
    // ────────────────────────────────────────────────────────────

    /** 加载并播放。返回 true 表示命令已受理（不代表已开始出声）。 */
    fun load(uri: String): Boolean = check { nativeLoad(uri) } == 0

    fun togglePause(): Boolean = check0(::nativeTogglePause) == 0

    fun setPaused(paused: Boolean): Boolean = check { nativeSetPaused(paused) } == 0

    fun stop(): Boolean = check0(::nativeStop) == 0

    fun seekTo(seconds: Double): Boolean = check { nativeSeekAbsolute(seconds) } == 0

    fun seekBy(deltaSeconds: Double): Boolean = check { nativeSeekRelative(deltaSeconds) } == 0

    fun setVolume(volume: Double): Boolean = check { nativeSetVolume(volume) } == 0

    fun setSpeed(speed: Double): Boolean = check { nativeSetSpeed(speed) } == 0

    /**
     * 设置 EQ / 音效滤镜链（对应桌面版的 `af` 命令）。
     *
     * 例：10 段 EQ 里提升 100Hz、衰减 1kHz：
     * ```
     * setAudioFilters("equalizer=f=100:t=q:w=1:g=3,equalizer=f=1000:t=q:w=1:g=-2")
     * ```
     *
     * ⚠️ 设置滤镜链会触发 mpv 重建 `af`（若含 `afir` 卷积会较重），
     * **不要在 UI 线程调用**。
     */
    fun setAudioFilters(chain: String): Boolean = check { nativeSetAudioFilters(chain) } == 0

    // ────────────────────────────────────────────────────────────
    // 高频属性（按渲染帧主动拉取，不要走事件）
    // ────────────────────────────────────────────────────────────

    /** 当前播放位置（毫秒）。引擎未就绪返回 0。 */
    fun getTimePosMs(): Long = if (isInitialized) nativeGetTimePosMs().coerceAtLeast(0) else 0

    /** 总时长（毫秒）。 */
    fun getDurationMs(): Long = if (isInitialized) nativeGetDurationMs().coerceAtLeast(0) else 0

    /** 是否暂停。 */
    fun isPaused(): Boolean = isInitialized && nativeIsPaused()

    /** 是否空闲（没有加载任何内容）。 */
    fun isIdle(): Boolean = !isInitialized || nativeIsIdle()

    /** 当前音量（0–100）。 */
    fun getVolume(): Double = if (isInitialized) nativeGetVolume() else 0.0

    /** 播放进度（0.0–1.0）。总时长未知时返回 0。 */
    fun getProgress(): Float {
        val dur = getDurationMs()
        if (dur <= 0) return 0f
        return (getTimePosMs().toDouble() / dur.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    // ────────────────────────────────────────────────────────────
    // 音频参数（高解析验证）
    // ────────────────────────────────────────────────────────────

    /**
     * 读取当前音频流参数。
     *
     * **这是 Demo 阶段验证「24bit/96kHz 未被降采样」的核心接口。**
     * 返回 null 表示尚不可用（文件未加载完成）。
     */
    fun getAudioParams(): AudioParams? {
        if (!isInitialized) return null
        val json = nativeGetAudioParams() ?: return null
        return AudioParams.fromJson(json)
    }

    /** 读任意字符串属性（排障用）。 */
    fun getProperty(name: String): String? =
        if (isInitialized) nativeGetPropertyString(name) else null

    // ────────────────────────────────────────────────────────────
    // 诊断
    // ────────────────────────────────────────────────────────────

    /** 引擎（Rust 侧）版本。 */
    fun engineVersion(): String? = try {
        if (isInitialized) nativeEngineVersion() else null
    } catch (t: Throwable) {
        null
    }

    /**
     * 探测 libmpv 能否加载并返回其 API 版本。
     *
     * **这是最轻量的「.so 是否可用」探针** ——
     * 不创建引擎、不初始化，只 dlopen + 读版本号。
     * 返回 null 表示加载失败。
     */
    fun probeLibmpvApiVersion(libPath: String = "libmpv.so"): Long? {
        val v = nativeLibmpvApiVersion(libPath)
        return if (v < 0) null else v
    }

    // ────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────

    private inline fun check(block: () -> Int): Int {
        if (!isInitialized) {
            lastError = "引擎未初始化 —— 请先调用 create()"
            return -1
        }
        val rc = block()
        if (rc != 0) lastError = "native 调用返回 $rc"
        return rc
    }

    private inline fun check0(block: () -> Int): Int = check(block)
}

/**
 * 事件回调接口。
 *
 * ## ⚠️ 线程约定
 *
 * **[onEvent] 在 Rust 的事件线程上被调用，不是主线程。**
 * 实现者若需更新 UI，必须自行切回主线程，例如：
 *
 * ```kotlin
 * object : MpvEventCallback {
 *     private val main = Handler(Looper.getMainLooper())
 *     override fun onEvent(type: Int, argF64: Double, argI64: Long, text: String?) {
 *         main.post { handleOnMain(type, argF64, argI64, text) }
 *     }
 * }
 * ```
 *
 * ## 性能约定
 *
 * [onEvent] **必须快速返回**。它内部应只做「投递到消息队列」这类
 * O(1) 操作 —— 在里面做耗时工作会阻塞 mpv 事件循环，导致播放卡顿。
 *
 * 另外：**播放进度不会通过本回调推送**（高频）。请按渲染帧主动调
 * [MpvEngine.getTimePosMs]。
 */
interface MpvEventCallback {
    /**
     * @param type   事件类型，见 [MpvEventType]
     * @param argF64 浮点载荷（如 time-pos，但高频值不走这里）
     * @param argI64 整数载荷（布尔用 0/1；log 时的 level 编码）
     * @param text   字符串载荷（路径、错误、日志）；无则为 null
     */
    fun onEvent(type: Int, argF64: Double, argI64: Long, text: String?)
}

/**
 * 事件类型编码。
 *
 * ⚠️ **这些数值是与 Rust 侧的硬契约**（`yan-engine-core/src/bridge.rs`
 * 的 `EventType`）。**不得改动已有数值**，新增事件只能追加新编号。
 * Rust 侧有单测 `event_type_encoding_is_stable` 守护这一点。
 */
object MpvEventType {
    /** argF64 = 播放位置（秒）。注意：高频，实际不走事件，仅为契约完整性保留 */
    const val TIME_UPDATE = 1
    /** argF64 = 时长（秒） */
    const val DURATION_CHANGE = 2
    /** argI64 = 1(已暂停) / 0(播放中) */
    const val STATE_CHANGE = 3
    /** text = "eof" | "stop" | "error" */
    const val PLAYBACK_END = 4
    /** text = 文件路径 */
    const val FILE_LOADED = 5
    /** 进入空闲 */
    const val IDLE = 6
    /** text = 错误描述 */
    const val ERROR = 7
    /** text = 日志内容；argI64 = level 编码（1=fatal…7=trace） */
    const val LOG_MESSAGE = 8
}

/**
 * 音频流参数 —— 高解析验证的载体。
 */
data class AudioParams(
    /** 采样率（Hz），如 96000 */
    val samplerate: Int,
    /** 声道数 */
    val channels: Int,
    /** 采样格式，如 "s32" / "s16" / "float" */
    val format: String,
    /** 编码名，如 "flac" / "ape" / "alac" */
    val codec: String,
) {
    /** 是否达到高解析标准（≥ 88.2kHz）。 */
    val isHiRes: Boolean get() = samplerate >= 88200

    /** 是否无损编码。 */
    val isLossless: Boolean
        get() = codec.lowercase() in setOf("flac", "alac", "ape", "wavpack", "tta", "tak", "wav")

    /**
     * 是否疑似被降采样。
     *
     * 已知常见高解析采样率集合；若实际值明显不在其中且低于预期，
     * 可能发生了重采样。仅作提示，不是绝对判据。
     */
    fun seemsDownsampled(expected: Int): Boolean = samplerate < expected

    companion object {
        /** 从 Rust 侧返回的 JSON 解析。手写解析避免引入 JSON 依赖。 */
        fun fromJson(json: String): AudioParams? {
            return try {
                AudioParams(
                    samplerate = extractInt(json, "samplerate") ?: return null,
                    channels = extractInt(json, "channels") ?: 0,
                    format = extractString(json, "format") ?: "",
                    codec = extractString(json, "codec") ?: "",
                )
            } catch (t: Throwable) {
                null
            }
        }

        private fun extractInt(json: String, key: String): Int? {
            val m = Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        private fun extractString(json: String, key: String): String? {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json) ?: return null
            return m.groupValues[1]
        }
    }
}
