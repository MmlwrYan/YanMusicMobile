package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.EmptyState
import com.yanmusic.engine.ui.components.SectionHeader
import com.yanmusic.engine.ui.components.SongRow
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 音乐库。
 *
 * 首版这里承载**真实可播放内容**：
 * 1. 用户通过系统文件选择器导入的本地音频；
 * 2. 随包内置的试听曲（用于验证高解析链路）。
 *
 * 其余（收藏 / 最近播放）是入口，详情在各自页面。
 */
@Composable
fun LibraryScreen(
    repository: MusicRepository,
    player: PlayerController,
    onImportFiles: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecent: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
) {
    val palette = YanTheme.palette

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = YanSpace.screenH,
            end = YanSpace.screenH,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "音乐库",
                color = palette.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        item {
            QuickEntryRow(
                recentCount = repository.recent.size,
                favoriteCount = repository.favorites.size,
                localCount = repository.localSongs.size,
                onOpenRecent = onOpenRecent,
                onOpenFavorites = onOpenFavorites,
                onOpenLocal = onImportFiles,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }

        item {
            SectionHeader(
                title = "本地音乐",
                subtitle = "${repository.localSongs.size} 首 · 导入会建立可播放的本地引用",
                actionLabel = "导入",
                onAction = onImportFiles,
            )
        }

        if (repository.localSongs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "还没有本地音乐",
                    description = "从系统文件选择器里挑几首音频（支持 flac / wav / mp3 / m4a 等），" +
                        "导入后即可播放并查看实时音频参数。",
                    actionLabel = "导入文件",
                    onAction = onImportFiles,
                )
            }
        } else {
            itemsIndexed(repository.localSongs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    isCurrent = player.current?.id == song.id,
                    isPlaying = player.isPlaying,
                    showCover = false,
                    onClick = { onPlaySong(song, repository.localSongs) },
                )
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
        }

        item {
            SectionHeader(
                title = "我的收藏",
                subtitle = if (repository.favorites.isEmpty()) "还没有收藏" else "${repository.favorites.size} 首",
                actionLabel = "查看",
                onAction = onOpenFavorites,
            )
        }

        items(repository.favorites.take(5), key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrent = player.current?.id == song.id,
                isPlaying = player.isPlaying,
                onClick = { onPlaySong(song, repository.favorites) },
            )
        }

        item {
            Spacer(Modifier.height(18.dp))
        }

        item {
            SectionHeader(
                title = "最近播放",
                subtitle = if (repository.recent.isEmpty()) "还没有播放记录" else "${repository.recent.size} 首",
                actionLabel = "查看",
                onAction = onOpenRecent,
            )
        }

        items(repository.recent.take(5), key = { it.id }) { song ->
            SongRow(
                song = song,
                isCurrent = player.current?.id == song.id,
                isPlaying = player.isPlaying,
                onClick = { onPlaySong(song, repository.recent) },
            )
        }
    }
}
