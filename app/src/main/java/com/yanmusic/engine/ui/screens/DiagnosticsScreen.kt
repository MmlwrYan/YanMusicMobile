package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.MpvEngine
import com.yanmusic.engine.player.EngineState
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.PrimaryButton
import com.yanmusic.engine.ui.components.YanTopBar
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 引擎诊断。
 *
 * 由原「技术验证 Demo」收敛而来。**刻意不在这里创建第二个引擎** ——
 * 播放用的引擎已由 [PlayerController] 单例持有，重复 `create()` 会打架。
 * 这里只做三件安全的事：
 *
 * 1. 展示引擎当前状态与版本（读）;
 * 2. 主动 dlopen 探测 libmpv（只读，不 init）;
 * 3. 展示当前曲目的实时音频参数（高解析验证的最终判据）。
 */
@Composable
fun DiagnosticsScreen(
    player: PlayerController,
    onBack: () -> Unit,
) {
    val palette = YanTheme.palette
    var probeText by remember { mutableStateOf("尚未探测") }
    var probeOk by remember { mutableStateOf<Boolean?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        YanTopBar(title = "引擎诊断", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = YanSpace.screenH,
                end = YanSpace.screenH,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiagCard(title = "① 引擎") {
                    Kv("状态", engineText(player.engineState), highlight = player.engineState is EngineState.Ready)
                    Kv("版本", player.let { runCatching { MpvEngine.engineVersion() }.getOrNull() ?: "—" })
                    Kv("原生库", if (MpvEngine.isInitialized) "已加载" else "未加载")
                    if (player.engineState is EngineState.Failed) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = (player.engineState as EngineState.Failed).reason,
                            color = palette.danger,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            item {
                DiagCard(title = "② libmpv 探针（只 dlopen，不初始化）") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        PrimaryButton(
                            text = "重新探测",
                            icon = Icons.Rounded.Refresh,
                            onClick = {
                                val api = runCatching { MpvEngine.probeLibmpvApiVersion() }.getOrNull()
                                if (api == null) {
                                    probeText = "✗ 无法加载 libmpv（.so 缺失或依赖不全）"
                                    probeOk = false
                                } else {
                                    val major = (api shr 16) and 0xFFFF
                                    val minor = api and 0xFFFF
                                    probeText = "✓ libmpv API $major.$minor（原始值 $api）"
                                    probeOk = true
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = probeText,
                        color = when (probeOk) {
                            true -> palette.success
                            false -> palette.danger
                            null -> palette.textTertiary
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            item {
                DiagCard(title = "③ 当前音频参数（高解析判据）") {
                    val p = player.audioParams
                    if (p == null) {
                        Text(
                            text = "尚未读取到音频参数。播放一首本地曲目后自动填充。",
                            color = palette.textTertiary,
                            fontSize = 12.sp,
                        )
                    } else {
                        Kv("采样率", "${p.samplerate} Hz", highlight = true)
                        Kv("声道", "${p.channels}")
                        Kv("采样格式", p.format)
                        Kv("编码", p.codec)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (p.isHiRes) "✓ 高解析（≥ 88.2 kHz）" else "⚠ 未达高解析",
                            color = if (p.isHiRes) palette.success else palette.warning,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "判据：播放 96 kHz 的文件若读回 48000，说明被重采样了。",
                            color = palette.textTertiary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun engineText(state: EngineState): String = when (state) {
    is EngineState.Idle -> "未启动"
    is EngineState.Loading -> "启动中"
    is EngineState.Ready -> "就绪"
    is EngineState.Failed -> "失败"
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    val palette = YanTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            color = palette.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Kv(label: String, value: String, highlight: Boolean = false) {
    val palette = YanTheme.palette
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label：",
            color = palette.textTertiary,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = value,
            color = if (highlight) palette.success else palette.textPrimary,
            fontSize = if (highlight) 14.sp else 12.5.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}
