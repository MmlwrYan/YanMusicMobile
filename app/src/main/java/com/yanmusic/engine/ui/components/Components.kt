package com.yanmusic.engine.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.Playlist
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme
import kotlin.math.absoluteValue

// ────────────────────────────────────────────────────────────────
// 封面
// ────────────────────────────────────────────────────────────────

/**
 * 由字符串种子确定的渐变封面。
 *
 * 首版没有图片资源（也不联网拉封面），用**确定性渐变**代替：
 * 同一个 id 永远得到同一组颜色，列表滚动时不会闪变。
 * 接上真实封面后，这里换成 `AsyncImage` 即可，调用方无需改动。
 */
private val COVER_GRADIENTS: List<Pair<Color, Color>> = listOf(
    Color(0xFF2C5364) to Color(0xFF0F2027),
    Color(0xFF4568DC) to Color(0xFF1B2A6B),
    Color(0xFF8E2DE2) to Color(0xFF4A00E0),
    Color(0xFF11998E) to Color(0xFF12564C),
    Color(0xFFEE9CA7) to Color(0xFF7A4A55),
    Color(0xFFF8B195) to Color(0xFF6C5B7B),
    Color(0xFF00B4DB) to Color(0xFF00506B),
    Color(0xFFCB356B) to Color(0xFF5A1436),
    Color(0xFF3A7BD5) to Color(0xFF1B3B6F),
    Color(0xFFD38312) to Color(0xFF5C3A0A),
    Color(0xFF7B4397) to Color(0xFF33174A),
    Color(0xFF93A5CF) to Color(0xFF3E4A6B),
)

@Composable
fun GradientCover(
    seed: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    iconSize: Dp = 22.dp,
    showIcon: Boolean = true,
) {
    val (start, end) = COVER_GRADIENTS[seed.hashCode().absoluteValue % COVER_GRADIENTS.size]
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center,
    ) {
        if (showIcon) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────
// 基础控件
// ────────────────────────────────────────────────────────────────

/** 圆形图标按钮，带主题色可选。 */
@Composable
fun YanIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = YanTheme.palette.textSecondary,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else YanTheme.palette.textTertiary.copy(alpha = 0.5f),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** 区块标题 + 可选右侧动作。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = YanTheme.palette.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = YanTheme.palette.textTertiary,
                    fontSize = 12.sp,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = actionLabel,
                    color = YanTheme.palette.textSecondary,
                    fontSize = 13.sp,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = YanTheme.palette.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 药丸筛选标签。 */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = YanTheme.palette
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) palette.accent else palette.card)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.border,
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else palette.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 空态占位。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = YanTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = YanSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(palette.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            color = palette.textTertiary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            PrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** 主行动按钮（胶囊）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = YanTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) palette.accent else palette.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = text,
            color = if (enabled) Color.White else palette.textTertiary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 次级按钮（描边）。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val palette = YanTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.card)
            .border(1.dp, palette.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = text,
            color = palette.textSecondary,
            fontSize = 14.sp,
        )
    }
}

// ────────────────────────────────────────────────────────────────
// 列表项
// ────────────────────────────────────────────────────────────────

/** 歌曲行。 */
@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    index: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    showCover: Boolean = true,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    val palette = YanTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isCurrent && isPlaying) "▶" else index.toString(),
                    color = if (isCurrent) palette.accent else palette.textTertiary,
                    fontSize = 13.sp,
                )
            }
        } else if (showCover) {
            GradientCover(
                seed = song.id,
                modifier = Modifier.size(44.dp),
                iconSize = 18.dp,
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = if (isCurrent) palette.accent else palette.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isHiRes) {
                    HiResBadge()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = if (song.album.isNotEmpty()) "${song.artist} · ${song.album}" else song.artist,
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!song.isPlayable) {
            Text(
                text = "示例",
                color = palette.textTertiary,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.rowHover)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }

        Text(
            text = formatDuration(song.durationMs),
            color = palette.textTertiary,
            fontSize = 12.sp,
        )

        if (onMore != null) {
            YanIconButton(
                icon = Icons.Rounded.MoreVert,
                contentDescription = "更多",
                onClick = onMore,
                size = 32.dp,
            )
        }
    }
}

/** 高解析角标。 */
@Composable
fun HiResBadge(modifier: Modifier = Modifier) {
    val palette = YanTheme.palette
    Text(
        text = "HI-RES",
        color = palette.warning,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, palette.warning.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 歌单卡片（网格用）。 */
@Composable
fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = YanTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box {
            GradientCover(
                seed = playlist.id,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
                iconSize = 34.dp,
            )
            // 播放量角标
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    text = formatCount(playlist.playCount),
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            color = palette.textPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = playlist.subtitle,
            color = palette.textTertiary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 顶部栏（自绘，不用 M3 TopAppBar 以避开 Experimental 标注）。 */
@Composable
fun YanTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val palette = YanTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = YanSpace.sm, vertical = YanSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            YanIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                tint = palette.textPrimary,
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = if (onBack != null) 17.sp else 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

// ────────────────────────────────────────────────────────────────
// 工具
// ────────────────────────────────────────────────────────────────

/** `mm:ss`；未知时长返回 `--:--`。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 播放量：1.2万 / 3.4亿。 */
fun formatCount(count: Int): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
}

/** 统一的内容内边距。 */
val screenPadding = PaddingValues(horizontal = YanSpace.screenH)
