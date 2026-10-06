package com.yanmusic.engine.demo

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.yanmusic.engine.AudioParams
import com.yanmusic.engine.MpvEngine
import com.yanmusic.engine.MpvEventCallback
import com.yanmusic.engine.MpvEventType

/**
 * 最小技术验证 Demo。
 *
 * ## 验证链路（对应路线图的 V1–V8）
 *
 * V1 `.so` 存在且架构正确  → 由 CI 的 verify-libs.sh 保证
 * V2 `System.loadLibrary` 不抛异常 → [MpvEngine.loadNativeLibraries]
 * V3 `mpv_create` 成功     → [MpvEngine.probeLibmpvApiVersion]
 * V4 `mpv_initialize` 成功 → [MpvEngine.create]
 * V5 加载本地 FLAC         → 选文件后 [MpvEngine.load]
 * V6 **采样率 == 96000**   → 面板上的「高解析验证」区
 * V7 `time-pos` 持续推进   → 进度条
 * V8 logcat 无解码错误     → 事件日志区
 */
class MainActivity : ComponentActivity() {

    /** 引擎事件在 Rust 线程到达 → 统一切到主线程再更新 UI。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 引擎事件日志（保留最近 N 条）。 */
    private val eventLog = mutableStateListOf<String>()

    /** 高解析验证结果。 */
    private var audioParams by mutableStateOf<AudioParams?>(null)

    /** 引擎探针结果（V3）。 */
    private var probeResult by mutableStateOf<String>("尚未探测")

    /**
     * 初始化状态。
     *
     * ⚠️ 必须显式标注 `mutableStateOf<InitState>(...)` ——
     *    若写成 `mutableStateOf(InitState.NotStarted)`，Kotlin 会把泛型
     *    推断成 `InitState.NotStarted`（这个 data object 的具体类型），
     *    后续 `initState = InitState.Ready` 就会报类型不匹配。
     */
    private var initState: InitState by mutableStateOf<InitState>(InitState.NotStarted)

    /** 事件回调 —— ⚠️ 在 Rust 事件线程被调用。 */
    private val callback = object : MpvEventCallback {
        override fun onEvent(type: Int, argF64: Double, argI64: Long, text: String?) {
            // 只在本线程做 O(1) 的投递，重活交给主线程
            mainHandler.post {
                val name = when (type) {
                    MpvEventType.TIME_UPDATE -> "TIME_UPDATE"
                    MpvEventType.DURATION_CHANGE -> "DURATION_CHANGE"
                    MpvEventType.STATE_CHANGE -> "STATE_CHANGE"
                    MpvEventType.PLAYBACK_END -> "PLAYBACK_END"
                    MpvEventType.FILE_LOADED -> "FILE_LOADED"
                    MpvEventType.IDLE -> "IDLE"
                    MpvEventType.ERROR -> "ERROR"
                    MpvEventType.LOG_MESSAGE -> "LOG"
                    else -> "UNKNOWN($type)"
                }
                val line = buildString {
                    append(name)
                    if (argF64 != 0.0) append(" f=$argF64")
                    if (argI64 != 0L) append(" i=$argI64")
                    if (!text.isNullOrEmpty()) append(" 「$text」")
                }
                eventLog.add(line)
                if (eventLog.size > 60) eventLog.removeAt(0)

                // 文件加载完成 → 立即读取音频参数（V6 验证）
                if (type == MpvEventType.FILE_LOADED) {
                    // 稍等一拍，等 mpv 把 audio-params 填好
                    mainHandler.postDelayed({ refreshAudioParams() }, 400)
                }
            }
        }
    }

    /** SAF 选文件。 */
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            eventLog.add("选择了文件：$uri")
            // Demo：直接用 content:// URI。libmpv 需要 fd 路径，
            // 但 mpv 的 Android 构建通常支持 content:// 或需转 /proc/self/fd。
            // 这里先传原始 URI，若失败会在事件里看到 error。
            val ok = MpvEngine.load(uri.toString())
            eventLog.add(if (ok) "load 命令已受理" else "load 失败：${MpvEngine.lastError}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF0F0F14), modifier = Modifier.fillMaxSize()) {
                    DemoScreen(
                        initState = initState,
                        probeResult = probeResult,
                        eventLog = eventLog,
                        audioParams = audioParams,
                        onLoadLibs = { doLoadLibraries() },
                        onInit = { doInitEngine() },
                        onPickFile = { pickAudio.launch(arrayOf("audio/*")) },
                        onPlayPause = {
                            MpvEngine.togglePause()
                            eventLog.add("togglePause → paused=${MpvEngine.isPaused()}")
                        },
                        onStop = {
                            MpvEngine.stop()
                            audioParams = null
                        },
                        onProbeOnly = { doProbeOnly() },
                    )
                }
            }
        }

        // 启动时自动跑一次「加载库 + 探测」，让 Demo 一打开就有结果
        doLoadLibraries()
    }

    override fun onDestroy() {
        MpvEngine.destroy()
        super.onDestroy()
    }

    // ── 步骤动作 ──

    private fun doLoadLibraries() {
        val err = MpvEngine.loadNativeLibraries()
        initState = if (err == null) {
            InitState.LibsLoaded
        } else {
            InitState.Failed(err)
        }
        eventLog.add(
            if (err == null) "✓ V2 原生库加载成功" else "✗ V2 原生库加载失败：$err"
        )
    }

    private fun doProbeOnly() {
        // V3：不初始化引擎，只 dlopen 读版本
        val api = MpvEngine.probeLibmpvApiVersion()
        probeResult = if (api == null) {
            "✗ libmpv 加载失败（.so 缺失或依赖不全）"
        } else {
            // mpv API 版本编码：major<<16 | minor
            val major = (api shr 16) and 0xFFFF
            val minor = api and 0xFFFF
            "✓ libmpv API $major.$minor（原始值 $api）"
        }
        eventLog.add("V3 探测：$probeResult")
    }

    private fun doInitEngine() {
        // V4：创建并初始化引擎
        val ok = MpvEngine.create(callback = callback, audioOutput = "aaudio")
        initState = if (ok) {
            InitState.Ready
        } else {
            InitState.Failed(MpvEngine.lastError ?: "未知错误")
        }
        eventLog.add(
            if (ok) "✓ V4 引擎初始化成功（版本 ${MpvEngine.engineVersion()}）"
            else "✗ V4 引擎初始化失败：${MpvEngine.lastError}"
        )
    }

    private fun refreshAudioParams() {
        val p = MpvEngine.getAudioParams()
        audioParams = p
        if (p != null) {
            val verdict = when {
                p.isHiRes -> "✓ V6 高解析达成（≥88.2kHz）"
                else -> "⚠ V6 未达高解析（${p.samplerate} Hz）"
            }
            eventLog.add("$verdict：${p.samplerate}Hz / ${p.channels}ch / ${p.codec} / ${p.format}")
        }
    }
}

/** 初始化阶段状态。 */
sealed interface InitState {
    /** 尚未开始 */
    data object NotStarted : InitState
    /** 原生库已加载（V2 通过），引擎未创建 */
    data object LibsLoaded : InitState
    /** 引擎已就绪（V4 通过） */
    data object Ready : InitState
    /** 失败 */
    data class Failed(val reason: String) : InitState
}
