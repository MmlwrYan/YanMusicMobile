package com.yanmusic.engine.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.AudioParams
import com.yanmusic.engine.MpvEngine
import kotlinx.coroutines.delay

/**
 * Demo 主界面。
 *
 * 布局分四块，对应验证清单 V1–V8：
 * 1. 步骤按钮区（加载库 → 探测 → 初始化）
 * 2. 高解析验证区（V6，最重要）
 * 3. 播放控制区
 * 4. 事件日志区（V8 排障用）
 */
@Composable
fun DemoScreen(
    initState: InitState,
    probeResult: String,
    eventLog: List<String>,
    audioParams: AudioParams?,
    onLoadLibs: () -> Unit,
    onInit: () -> Unit,
    onPickFile: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onProbeOnly: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()

        // ── 1. 步骤区 ──
        SectionCard(title = "① 引擎初始化（V2–V4）") {
            StatusRow(label = "原生库", state = initState)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("重载库", onClick = onLoadLibs)
                SmallButton("仅探测 .so", onClick = onProbeOnly)
                SmallButton(
                    "创建引擎",
                    onClick = onInit,
                    enabled = initState is InitState.LibsLoaded || initState is InitState.Ready,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                probeResult,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (probeResult.startsWith("✓")) OkGreen else WarnAmber,
            )
        }

        // ── 2. 高解析验证区（核心）──
        SectionCard(title = "② 高解析验证（V6）") {
            if (audioParams == null) {
                Text(
                    "尚未加载音频。选一个 24bit/96kHz 的 FLAC 试试。",
                    fontSize = 13.sp,
                    color = MutedText,
                )
            } else {
                HiResReport(audioParams)
            }
        }

        // ── 3. 播放控制 ──
        SectionCard(title = "③ 播放控制（V5 / V7）") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("选择音频文件", onClick = onPickFile, enabled = isEngineReady(initState))
            }
            Spacer(Modifier.height(10.dp))
            if (isEngineReady(initState)) {
                ProgressBar()
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("播放/暂停", onClick = onPlayPause)
                    SmallButton("停止", onClick = onStop)
                }
            } else {
                Text("引擎未就绪", fontSize = 12.sp, color = MutedText)
            }
        }

        // ── 4. 事件日志 ──
        SectionCard(title = "④ 事件日志（V8 排障）") {
            if (eventLog.isEmpty()) {
                Text("（无事件）", fontSize = 12.sp, color = MutedText)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    eventLog.reversed().forEach { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                line.startsWith("✓") -> OkGreen
                                line.startsWith("✗") -> ErrRed
                                line.startsWith("⚠") -> WarnAmber
                                else -> LogText
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            "YanMusic Engine — 技术验证 Demo",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            "验证「自建 libmpv + Rust JNI 桥接」链路",
            fontSize = 12.sp,
            color = MutedText,
        )
    }
}

@Composable
private fun StatusRow(label: String, state: InitState) {
    val (text, color) = when (state) {
        InitState.NotStarted -> "未开始" to MutedText
        InitState.LibsLoaded -> "✓ 已加载（V2 通过）" to OkGreen
        InitState.Ready -> "✓ 已就绪（V4 通过）" to OkGreen
        is InitState.Failed -> "✗ ${state.reason}" to ErrRed
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label：", fontSize = 13.sp, color = Color.White)
        Text(text, fontSize = 13.sp, color = color)
    }
}

/**
 * 高解析报告 —— Demo 的核心产出。
 *
 * 判据：96kHz 的 FLAC 若采样率读出来仍是 96000，
 * 说明 libmpv **没有**把它重采样到 48kHz 输出，
 * 「音质优先」的根基在 Android 上守住了。
 */
@Composable
private fun HiResReport(p: AudioParams) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KeyValue("采样率", "${p.samplerate} Hz", highlight = true)
        KeyValue("声道", "${p.channels}")
        KeyValue("编码", p.codec)
        KeyValue("采样格式", p.format)
        Spacer(Modifier.height(6.dp))
        val verdict = if (p.isHiRes) "✓ 高解析（≥88.2kHz）" else "⚠ 未达高解析"
        Text(
            verdict,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (p.isHiRes) OkGreen else WarnAmber,
        )
        if (p.isLossless) {
            Text("✓ 无损编码", fontSize = 12.sp, color = OkGreen)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "对照：若播放 96kHz 文件却读到 48000，说明被降采样了。",
            fontSize = 11.sp,
            color = MutedText,
        )
    }
}

@Composable
private fun KeyValue(label: String, value: String, highlight: Boolean = false) {
    Row {
        Text(
            "$label：",
            fontSize = 13.sp,
            color = MutedText,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            fontSize = if (highlight) 15.sp else 13.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) OkGreen else Color.White,
        )
    }
}

/** 进度条 —— 演示「按渲染帧主动拉取」而非靠事件推送。 */
@Composable
private fun ProgressBar() {
    var posMs by remember { mutableStateOf(0L) }
    var durMs by remember { mutableStateOf(0L) }

    // 每 100ms 主动拉一次（真实产品里用 Choreographer 按 16ms 拉）
    LaunchedEffect(Unit) {
        while (true) {
            posMs = MpvEngine.getTimePosMs()
            durMs = MpvEngine.getDurationMs()
            delay(100)
        }
    }

    val progress = if (durMs > 0) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f

    Column {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = Accent,
            trackColor = Color(0xFF2A2A35),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${fmt(posMs)} / ${fmt(durMs)}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
        )
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171720), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF2A2A35), RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Accent,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SmallButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) Color(0xFF2C2C3A) else Color(0xFF1E1E26),
            contentColor = if (enabled) Color.White else MutedText,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp)
    }
}

private fun isEngineReady(state: InitState): Boolean = state is InitState.Ready

// ── 配色（深色主题，与桌面版风格一致）──
private val Accent = Color(0xFF7AA2F7)
private val OkGreen = Color(0xFF9ECE6A)
private val ErrRed = Color(0xFFF7768E)
private val WarnAmber = Color(0xFFE0AF68)
private val MutedText = Color(0xFF8A8A9A)
private val LogText = Color(0xFFB0B0C0)
