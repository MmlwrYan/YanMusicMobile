package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.Banner
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Playlist
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.LayoutSpec
import com.yanmusic.engine.ui.components.GradientCover
import com.yanmusic.engine.ui.components.PlaylistCard
import com.yanmusic.engine.ui.components.SectionHeader
import com.yanmusic.engine.ui.components.SongRow
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 首页（推荐）。
 *
 * 结构对齐主流音乐软件：
 * 顶部问候语 + 搜索入口 → 主推横幅（横滑）→ 快捷入口 → 推荐歌单（自适应网格）→ 猜你喜欢（歌曲列表）。
 *
 * 网格用 [GridCells.Adaptive]，列数**由可用宽度自动决定** ——
 * 手机 2 列、折叠屏 3 列、平板 4–5 列，不需要写 breakpoint 判断。
 */
@Composable
fun HomeScreen(
    repository: MusicRepository,
    player: PlayerController,
    spec: LayoutSpec,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenBanner: (Banner) -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onSeeAllRecommended: () -> Unit,
) {
    val palette = YanTheme.palette

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 148.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YanSpace.screenH,
            end = YanSpace.screenH,
            bottom = 28.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            HomeGreeting()
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            BannerRow(banners = repository.banners, onOpen = onOpenBanner)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(2.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = "推荐歌单",
                subtitle = "根据最近的收听整理",
                actionLabel = "更多",
                onAction = { onOpenPlaylist(repository.playlists.first()) },
            )
        }

        items(repository.playlists, key = { it.id }) { playlist ->
            PlaylistCard(
                playlist = playlist,
                onClick = { onOpenPlaylist(playlist) },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(6.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                title = "猜你喜欢",
                subtitle = "示例条目，导入本地音乐后即可播放",
                actionLabel = "全部",
                onAction = onSeeAllRecommended,
            )
        }

        items(repository.recommendedSongs, key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrent = player.current?.id == song.id,
                isPlaying = player.isPlaying,
                onClick = { onPlaySong(song, repository.recommendedSongs) },
            )
        }
    }
}

@Composable
private fun HomeGreeting() {
    val palette = YanTheme.palette
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = greetingText(),
            color = palette.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        // 搜索入口（首版无搜索后端，点击给提示）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(palette.card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "搜索歌曲、歌手、专辑",
                color = palette.textTertiary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun BannerRow(
    banners: List<Banner>,
    onOpen: (Banner) -> Unit,
) {
    val palette = YanTheme.palette
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        lazyItems(banners, key = { it.id }) { banner ->
            Box(
                modifier = Modifier
                    .width(268.dp)
                    .aspectRatio(2.35f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpen(banner) },
            ) {
                GradientCover(
                    seed = banner.id,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(14.dp),
                    showIcon = false,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                            )
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp),
                ) {
                    Text(
                        text = banner.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = banner.subtitle,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("立即播放", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** 快捷入口：最近播放 / 我的收藏 / 导入音乐。（首页与音乐库页共用） */
@Composable
fun QuickEntryRow(
    recentCount: Int,
    favoriteCount: Int,
    localCount: Int,
    onOpenRecent: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLocal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickEntry("最近播放", recentCount, Icons.Rounded.History, onOpenRecent, Modifier.weight(1f))
        QuickEntry("我的收藏", favoriteCount, Icons.Rounded.Favorite, onOpenFavorites, Modifier.weight(1f))
        // 标签用「导入音乐」而不是「本地音乐」—— 它点击后打开的是系统文件选择器，
        // 写「本地音乐」会让人以为进入某个页面（本页本身就是本地音乐的所在）。
        QuickEntry("导入音乐", localCount, Icons.Rounded.LibraryMusic, onOpenLocal, Modifier.weight(1f))
    }
}

@Composable
private fun QuickEntry(
    label: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = YanTheme.palette
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(20.dp),
        )
        Text(label, color = palette.textPrimary, fontSize = 12.5.sp)
        Text(
            text = if (count > 0) "$count 首" else "—",
            color = palette.textTertiary,
            fontSize = 11.sp,
        )
    }
}

private fun greetingText(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 6 -> "夜深了"
        hour < 11 -> "早上好"
        hour < 14 -> "中午好"
        hour < 18 -> "下午好"
        hour < 23 -> "晚上好"
        else -> "夜深了"
    }
}
