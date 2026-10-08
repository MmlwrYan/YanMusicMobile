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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Playlist
import com.yanmusic.engine.data.RankBoard
import com.yanmusic.engine.ui.components.FilterChip
import com.yanmusic.engine.ui.components.GradientCover
import com.yanmusic.engine.ui.components.SectionHeader
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 发现页。
 *
 * 结构：分类筛选（横滑 chip）→ 排行榜（自适应网格）→ 精选歌单。
 *
 * 分类筛选是**本地过滤**（按歌单名匹配），首版没有曲库检索后端，
 * 但交互形态先立起来，接 API 时只需替换过滤函数。
 */
@Composable
fun DiscoverScreen(
    repository: MusicRepository,
    onOpenRank: (RankBoard) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
) {
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    val playlists = remember(selectedGenre) {
        if (selectedGenre == null) repository.playlists
        else repository.playlists.filter { it.name.contains(selectedGenre!!) || it.subtitle.contains(selectedGenre!!) }
            .ifEmpty { repository.playlists }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 190.dp),
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
            Text(
                text = "发现",
                color = YanTheme.palette.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lazyItems(listOf<String?>(null) + repository.genres.map { it.name }) { genre ->
                    val label = genre ?: "全部"
                    FilterChip(
                        text = label,
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(2.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = "排行榜", subtitle = "每小时更新（示例数据）")
        }

        items(repository.rankBoards, key = { it.id }) { board ->
            RankCard(board = board, onClick = { onOpenRank(board) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(6.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = "精选歌单")
        }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistWideCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
        }
    }
}

/** 排行榜卡：封面 + 名称 + 前三首。 */
@Composable
private fun RankCard(board: RankBoard, onClick: () -> Unit) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            GradientCover(
                seed = board.id,
                modifier = Modifier.size(64.dp),
                iconSize = 24.dp,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accent)
                    .padding(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = board.name,
                color = palette.textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = board.description,
                color = palette.textTertiary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            board.songs.take(3).forEachIndexed { i, song ->
                Text(
                    text = "${i + 1}. ${song.title} · ${song.artist}",
                    color = palette.textSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 横向排布的歌单卡（发现页用，信息更宽）。 */
@Composable
private fun PlaylistWideCard(playlist: Playlist, onClick: () -> Unit) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientCover(
            seed = playlist.id,
            modifier = Modifier.size(56.dp),
            iconSize = 22.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = palette.textPrimary,
                fontSize = 14.5.sp,
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
        Text(
            text = "${playlist.songs.size} 首",
            color = palette.textTertiary,
            fontSize = 11.5.sp,
        )
    }
}
