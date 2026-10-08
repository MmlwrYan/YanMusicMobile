package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.player.PlayMode
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.LayoutSpec
import com.yanmusic.engine.ui.WindowClass
import com.yanmusic.engine.ui.components.HiResBadge
import com.yanmusic.engine.ui.components.YanIconButton
import com.yanmusic.engine.ui.components.formatDuration
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 全屏播放器。
 *
 * ## 响应式
 *
 * - 窄屏（Compact）竖屏：封面在上、信息与控件在下（主流手机布局）。
 * - 宽屏或横屏：**左右分栏** —— 封面在左，信息/进度/控件在右。
 *   这是本页的核心自适应点，用 [BoxWithConstraints] 判断，而不是靠外部传参。
 */
@Composable
fun PlayerScreen(
    player: PlayerController,
    spec: LayoutSpec,
    repository: MusicRepository,
    onBack: () -> Unit,
) {
    val palette = YanTheme.palette
    val song = player.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        val wide = maxWidth >= 720.dp || (spec.windowClass != WindowClass.Compact && maxHeight < maxWidth)

        // 背景：用封面渐变做低透明度氛围层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.accentGlow.copy(alpha = 0.22f),
                            Color.Transparent,
                        )
                    )
                ),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YanIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "收起",
                    onClick = onBack,
                    tint = palette.textPrimary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "正在播放",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (song != null) {
                        Text(
                            text = song.album.ifEmpty { "未知专辑" },
                            color = palette.textTertiary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                YanIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    onClick = { },
                    tint = palette.textSecondary,
                )
            }

            if (song == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有在播放的内容。\n回到「音乐库」导入本地音乐，或从歌单里选一首。",
                        color = palette.textTertiary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            if (wide) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    CoverArt(songId = song.id, size = 300.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TrackMeta(player)
                        Spacer(Modifier.height(20.dp))
                        SeekBar(player)
                        Spacer(Modifier.height(14.dp))
                        ControlRow(player, repository)
                        Spacer(Modifier.height(14.dp))
                        VolumeRow(player)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = YanSpace.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CoverArt(songId = song.id, size = 260.dp)
                    Spacer(Modifier.height(28.dp))
                    TrackMeta(player)
                    Spacer(Modifier.height(22.dp))
                    SeekBar(player)
                    Spacer(Modifier.height(16.dp))
                    ControlRow(player, repository)
                    Spacer(Modifier.height(16.dp))
                    VolumeRow(player)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CoverArt(songId: String, size: Dp, modifier: Modifier = Modifier) {
    val palette = YanTheme.palette
    Box(
        modifier = modifier
            .widthIn(max = size)
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(palette.card, palette.elevated),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        com.yanmusic.engine.ui.components.GradientCover(
            seed = songId,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            iconSize = size / 4,
        )
    }
}

@Composable
private fun TrackMeta(player: PlayerController) {
    val palette = YanTheme.palette
    val song = player.current ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (player.audioParams?.isHiRes == true) {
                HiResBadge()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = song.title,
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = song.artist,
            color = palette.textSecondary,
            fontSize = 13.sp,
            maxLines = 1,
        )
        val params = player.audioParams
        if (params != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${params.samplerate / 1000.0} kHz · ${params.channels}ch · ${params.codec.uppercase()} · ${params.format}",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SeekBar(player: PlayerController) {
    val palette = YanTheme.palette
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val value = if (dragging) dragValue else player.progress
    val shownPosition = if (dragging) {
        (player.durationMs * dragValue).toLong()
    } else {
        player.positionMs
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player.seekToFraction(dragValue)
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.track,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDuration(shownPosition),
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatDuration(player.durationMs),
                color = palette.textTertiary,
                fontSize = 11.5.sp,
            )
        }
    }
}

@Composable
private fun ControlRow(player: PlayerController, repository: MusicRepository) {
    val palette = YanTheme.palette
    val current = player.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // 播放模式
        val modeIcon: ImageVector = when (player.mode) {
            PlayMode.ListLoop -> Icons.Rounded.Repeat
            PlayMode.SingleLoop -> Icons.Rounded.RepeatOne
            PlayMode.Shuffle -> Icons.Rounded.Shuffle
        }
        YanIconButton(
            icon = modeIcon,
            contentDescription = player.mode.label,
            onClick = { player.cycleMode() },
            tint = if (player.mode == PlayMode.ListLoop) palette.textSecondary else palette.accent,
            size = 40.dp,
        )

        YanIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "上一首",
            onClick = { player.previous() },
            tint = palette.textPrimary,
            size = 52.dp,
        )

        // 播放/暂停主按钮
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(palette.accent)
                .clickable { player.togglePause() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (player.isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }

        YanIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "下一首",
            onClick = { player.next() },
            tint = palette.textPrimary,
            size = 52.dp,
        )

        val fav = current != null && repository.isFavorite(current)
        YanIconButton(
            icon = if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = "收藏",
            onClick = {
                if (current != null) repository.toggleFavorite(current)
            },
            tint = if (fav) palette.danger else palette.textSecondary,
            size = 40.dp,
        )
    }
}

@Composable
private fun VolumeRow(player: PlayerController) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = "音量",
            tint = palette.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Slider(
            value = player.volume / 100f,
            onValueChange = { player.updateVolume(it * 100f) },
            colors = SliderDefaults.colors(
                thumbColor = palette.textSecondary,
                activeTrackColor = palette.textSecondary,
                inactiveTrackColor = palette.track,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${player.volume.toInt()}",
            color = palette.textTertiary,
            fontSize = 11.5.sp,
            modifier = Modifier.width(26.dp),
        )
    }
}
