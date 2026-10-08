package com.yanmusic.engine.player

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yanmusic.engine.AudioParams
import com.yanmusic.engine.MpvEngine
import com.yanmusic.engine.MpvEventCallback
import com.yanmusic.engine.MpvEventType
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import kotlin.random.Random

/** 播放模式。 */
enum class PlayMode(val label: String) {
    ListLoop("列表循环"),
    SingleLoop("单曲循环"),
    Shuffle("随机播放");

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/** 引擎状态。 */
sealed interface EngineState {
    data object Idle : EngineState
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val reason: String) : EngineState
}

/**
 * 播放控制器 —— 首版的「播放中枢」。
 *
 * ## 设计要点
 *
 * 1. **进度用「拉」不用「推」**：桌面端与 Rust 侧的约定一致 ——
 *    `time-pos` 是高频属性，不走事件回调，而是由 UI 按帧调 [tick]。
 *    UI 侧每 250ms 拉一次（`LaunchedEffect` 里的循环）。
 * 2. **引擎懒初始化**：只有真正要播第一首歌时才 `create()`，
 *    避免一打开 App 就占住音频输出。
 * 3. **事件回调在 Rust 线程**：这里统一 `post` 回主线程再改状态。
 *    所有 `mutableStateOf` 的写入都发生在主线程，满足 Compose 的快照要求。
 * 4. 该类**不持有 Context 之外的 Android 资源**，由 `MainActivity` 在
 *    `onDestroy` 调 [release]。
 */
class PlayerController(
    private val repository: MusicRepository,
    private val resolver: LocalAudioResolver,
) {

    private val main = Handler(Looper.getMainLooper())

    // ── 对外可观察状态 ──

    var queue by mutableStateOf<List<Song>>(emptyList())
        private set

    var currentIndex by mutableStateOf(-1)
        private set

    /** 是否处于「正在播放」——暂停时为 false。 */
    var isPlaying by mutableStateOf(false)
        private set

    var isPaused by mutableStateOf(false)
        private set

    var positionMs by mutableStateOf(0L)
        private set

    var durationMs by mutableStateOf(0L)
        private set

    var volume by mutableStateOf(100f)
        private set

    var mode by mutableStateOf(PlayMode.ListLoop)
        private set

    var engineState by mutableStateOf<EngineState>(EngineState.Idle)
        private set

    /** 一次性提示（Snackbar 用）。读后请调 [consumeMessage]。 */
    var message by mutableStateOf<String?>(null)
        private set

    /** 当前曲目实测音频参数（读不到为 null）。 */
    var audioParams by mutableStateOf<AudioParams?>(null)
        private set

    val current: Song? get() = queue.getOrNull(currentIndex)

    /** 播放进度 0f–1f。 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f
        else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /** 是否有可显示的当前曲目。 */
    val hasCurrent: Boolean get() = current != null

    // ── 引擎事件 ──

    private val engineCallback = object : MpvEventCallback {
        override fun onEvent(type: Int, argF64: Double, argI64: Long, text: String?) {
            // 只做 O(1) 投递，重活回主线程
            main.post { handleEvent(type, argF64, argI64, text) }
        }
    }

    private fun handleEvent(type: Int, argF64: Double, argI64: Long, text: String?) {
        when (type) {
            MpvEventType.DURATION_CHANGE -> {
                val ms = (argF64 * 1000.0).toLong()
                if (ms > 0) durationMs = ms
            }

            MpvEventType.FILE_LOADED -> {
                isPlaying = true
                isPaused = false
                positionMs = 0L
                // 等 mpv 把 audio-params 填好再读（与 Demo 一致的做法）
                main.postDelayed({ refreshAudioParams() }, 400L)
            }

            MpvEventType.STATE_CHANGE -> {
                isPaused = argI64 == 1L
                isPlaying = !isPaused
            }

            MpvEventType.PLAYBACK_END -> onTrackEnded()

            MpvEventType.ERROR -> {
                message = "播放出错：${text ?: "未知原因"}"
                isPlaying = false
            }
        }
    }

    private fun refreshAudioParams() {
        val p = MpvEngine.getAudioParams() ?: return
        audioParams = p
        val dur = MpvEngine.getDurationMs()
        if (dur > 0) durationMs = dur
    }

    private fun onTrackEnded() {
        if (mode == PlayMode.SingleLoop) {
            replayCurrent()
        } else {
            next(auto = true)
        }
    }

    // ── 引擎生命周期 ──

    /**
     * 确保引擎就绪。
     *
     * @return 可用返回 true
     */
    fun ensureEngine(): Boolean {
        if (MpvEngine.isInitialized) {
            if (engineState !is EngineState.Ready) engineState = EngineState.Ready
            return true
        }
        engineState = EngineState.Loading

        val loadError = MpvEngine.loadNativeLibraries()
        if (loadError != null) {
            engineState = EngineState.Failed(loadError)
            return false
        }
        val ok = MpvEngine.create(callback = engineCallback, audioOutput = "aaudio")
        if (!ok) {
            engineState = EngineState.Failed(MpvEngine.lastError ?: "引擎初始化失败")
            return false
        }
        MpvEngine.setVolume(volume.toDouble())
        engineState = EngineState.Ready
        return true
    }

    // ── 播放控制 ──

    /**
     * 播放一首歌。
     *
     * @param newQueue 若非空则替换当前播放队列
     */
    fun play(song: Song, newQueue: List<Song>? = null) {
        if (!song.isPlayable) {
            message = "「${song.title}」是示例条目，未包含音频。请到「音乐库」导入本地文件。"
            return
        }
        if (!ensureEngine()) {
            message = "引擎不可用：${(engineState as? EngineState.Failed)?.reason ?: "未知原因"}"
            return
        }

        val path = resolver.resolve(song.source!!)
        if (path == null) {
            message = "无法读取该文件（可能已失效或权限被收回）"
            return
        }

        if (newQueue != null) queue = newQueue
        val idx = queue.indexOfFirst { it.id == song.id }
        currentIndex = if (idx >= 0) idx else {
            queue = listOf(song)
            0
        }

        positionMs = 0L
        durationMs = song.durationMs
        audioParams = null

        val accepted = MpvEngine.load(path)
        if (!accepted) {
            message = "播放失败：${MpvEngine.lastError ?: "load 被拒绝"}"
            isPlaying = false
            return
        }
        repository.markPlayed(song)
    }

    fun togglePause() {
        if (!MpvEngine.isInitialized) return
        if (current == null) return
        MpvEngine.togglePause()
        isPaused = MpvEngine.isPaused()
        isPlaying = !isPaused
    }

    fun pause() {
        if (!MpvEngine.isInitialized) return
        MpvEngine.setPaused(true)
        isPaused = true
        isPlaying = false
    }

    fun resume() {
        if (!MpvEngine.isInitialized) return
        MpvEngine.setPaused(false)
        isPaused = false
        isPlaying = true
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        val nextIdx = when (mode) {
            PlayMode.Shuffle -> if (queue.size <= 1) currentIndex else pickShuffleIndex()
            else -> (currentIndex + 1) % queue.size
        }
        if (!auto && queue.size == 1) {
            // 手动「下一首」在单曲队列里等于重播
            replayCurrent(); return
        }
        playAt(nextIdx)
    }

    fun previous() {
        if (queue.isEmpty()) return
        // 播放超过 3 秒时，上一首 = 回到开头（与主流播放器一致）
        if (positionMs > 3000L) {
            seekToMs(0L); return
        }
        val prevIdx = when (mode) {
            PlayMode.Shuffle -> if (queue.size <= 1) currentIndex else pickShuffleIndex()
            else -> if (currentIndex <= 0) queue.size - 1 else currentIndex - 1
        }
        playAt(prevIdx)
    }

    private fun playAt(index: Int) {
        val song = queue.getOrNull(index) ?: return
        currentIndex = index
        play(song)
    }

    private fun replayCurrent() {
        seekToMs(0L)
        resume()
    }

    private fun pickShuffleIndex(): Int {
        if (queue.size <= 1) return 0
        var idx: Int
        do {
            idx = Random.nextInt(queue.size)
        } while (idx == currentIndex)
        return idx
    }

    fun seekToMs(ms: Long) {
        if (!MpvEngine.isInitialized) return
        val target = ms.coerceIn(0L, if (durationMs > 0) durationMs else ms)
        MpvEngine.seekTo(target / 1000.0)
        positionMs = target
    }

    /** 按比例跳转（进度条拖拽用）。 */
    fun seekToFraction(fraction: Float) {
        if (durationMs <= 0L) return
        seekToMs((durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    /**
     * 调整音量。
     *
     * ⚠️ 方法名不能叫 `setVolume` —— `var volume` 的属性 setter 已占用
     *    JVM 签名 `setVolume(F)V`，会报 platform declaration clash。
     */
    fun updateVolume(v: Float) {
        volume = v.coerceIn(0f, 100f)
        if (MpvEngine.isInitialized) MpvEngine.setVolume(volume.toDouble())
    }

    fun cycleMode() {
        mode = mode.next()
        message = "播放模式：${mode.label}"
    }

    /** 由 UI 按帧调用，拉取高频属性。 */
    fun tick() {
        if (!MpvEngine.isInitialized) return
        if (current == null) return
        positionMs = MpvEngine.getTimePosMs()
        val dur = MpvEngine.getDurationMs()
        if (dur > 0L) durationMs = dur
        val declared = current?.durationMs ?: 0L
        if (durationMs <= 0L && declared > 0L) durationMs = declared

        // ⚠️ 这里**刻意不读 `MpvEngine.isIdle()`**。
        //    「是否正在播放」只由 mpv 的 `pause` 属性决定 —— 它是可靠且语义明确的；
        //    而 idle 标志是 Rust 侧自己维护的，未经真机验证。若它不准，
        //    播放按钮会每 250ms 被刷成「已暂停」，观感上直接是坏的。
        //    播放结束由 PLAYBACK_END 事件驱动（见 onTrackEnded），不依赖这里。
        isPaused = MpvEngine.isPaused()
        isPlaying = !isPaused
    }

    fun consumeMessage() {
        message = null
    }

    /** Activity 销毁时调用。 */
    fun release() {
        if (MpvEngine.isInitialized) {
            MpvEngine.stop()
            MpvEngine.destroy()
        }
        engineState = EngineState.Idle
        isPlaying = false
        resolver.release()
    }
}
