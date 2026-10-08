package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.demo.BuildConfig
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.YanTopBar
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 设置。
 *
 * 首版只保留真正生效的项（外观切换）+ 引擎诊断入口，
 * **不放无法兑现的开关** —— 假开关比没有开关更糟。
 */
@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    player: PlayerController,
) {
    val palette = YanTheme.palette

    Column(modifier = Modifier.fillMaxSize()) {
        YanTopBar(title = "设置", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = YanSpace.screenH,
                end = YanSpace.screenH,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SettingGroup(title = "外观") {
                    SwitchRow(
                        icon = Icons.Rounded.Palette,
                        title = "深色模式",
                        subtitle = "与桌面端一致的暗色主题",
                        checked = darkTheme,
                        onCheckedChange = { onToggleTheme() },
                    )
                }
            }

            item {
                SettingGroup(title = "播放") {
                    InfoRow(
                        icon = Icons.Rounded.Speed,
                        title = "音频输出",
                        value = "AAudio（低延迟）",
                    )
                    InfoRow(
                        icon = Icons.Rounded.Speed,
                        title = "播放模式",
                        value = player.mode.label,
                        onClick = { player.cycleMode() },
                    )
                }
            }

            item {
                SettingGroup(title = "引擎") {
                    InfoRow(
                        icon = Icons.Rounded.BugReport,
                        title = "引擎诊断",
                        value = engineLabel(player.engineState),
                        onClick = onOpenDiagnostics,
                    )
                }
            }

            item {
                SettingGroup(title = "关于") {
                    InfoRow(
                        icon = Icons.Rounded.Info,
                        title = "版本",
                        value = BuildConfig.VERSION_NAME,
                    )
                    InfoRow(
                        icon = Icons.Rounded.Info,
                        title = "许可",
                        value = "GPL-3.0",
                    )
                }
            }
        }
    }
}

private fun engineLabel(state: com.yanmusic.engine.player.EngineState): String = when (state) {
    is com.yanmusic.engine.player.EngineState.Idle -> "未启动"
    is com.yanmusic.engine.player.EngineState.Loading -> "启动中…"
    is com.yanmusic.engine.player.EngineState.Ready -> "就绪"
    is com.yanmusic.engine.player.EngineState.Failed -> "失败"
}

@Composable
private fun SettingGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = YanTheme.palette
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = palette.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),

        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card),
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = palette.textTertiary, fontSize = 11.5.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
            ),
        )
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null,
) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(value, color = palette.textTertiary, fontSize = 12.5.sp)
        }
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
