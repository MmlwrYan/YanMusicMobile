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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.demo.BuildConfig
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 「我的」页。
 *
 * 首版没有帐号体系（不做登录），因此这里定位为**本地档案 + 设置入口**：
 * 统计本地内容、进入收藏/最近播放/设置。
 */
@Composable
fun ProfileScreen(
    repository: MusicRepository,
    player: PlayerController,
    darkTheme: Boolean,
    onOpenSettings: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
) {
    val palette = YanTheme.palette

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YanSpace.screenH,
            end = YanSpace.screenH,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(palette.accent, palette.secondary),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "本地用户",
                            color = palette.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "YanMusic Mobile · ${BuildConfig.VERSION_NAME}",
                            color = palette.textTertiary,
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCell("本地", repository.localSongs.size, Modifier.weight(1f))
                    StatCell("收藏", repository.favorites.size, Modifier.weight(1f))
                    StatCell("最近", repository.recent.size, Modifier.weight(1f))
                }

                Spacer(Modifier.height(18.dp))
            }
        }

        item {
            MenuGroup {
                MenuRow(Icons.Rounded.Favorite, "我的收藏", "${repository.favorites.size} 首", onOpenFavorites)
                MenuRow(Icons.Rounded.History, "最近播放", "${repository.recent.size} 首", onOpenRecent)
            }
        }

        item { Spacer(Modifier.height(6.dp)) }

        item {
            MenuGroup {
                MenuRow(Icons.Rounded.Settings, "设置", null, onOpenSettings)
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "引擎状态：${engineLabel(player)}",
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private fun engineLabel(player: PlayerController): String = when (val s = player.engineState) {
    is com.yanmusic.engine.player.EngineState.Idle -> "未启动"
    is com.yanmusic.engine.player.EngineState.Loading -> "启动中"
    is com.yanmusic.engine.player.EngineState.Ready -> "就绪"
    is com.yanmusic.engine.player.EngineState.Failed -> "失败（${s.reason.take(24)}）"
}

@Composable
private fun StatCell(label: String, count: Int, modifier: Modifier = Modifier) {
    val palette = YanTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = count.toString(),
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(label, color = palette.textTertiary, fontSize = 11.5.sp)
    }
}

@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    val palette = YanTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card),
    ) {
        content()
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    trailing: String?,
    onClick: () -> Unit,
) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = palette.textTertiary, fontSize = 12.5.sp)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
