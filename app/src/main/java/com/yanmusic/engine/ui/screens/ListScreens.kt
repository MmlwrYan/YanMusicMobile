package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.EmptyState
import com.yanmusic.engine.ui.components.SongRow
import com.yanmusic.engine.ui.components.YanTopBar
import com.yanmusic.engine.ui.theme.YanSpace

/** 我的收藏。 */
@Composable
fun FavoritesScreen(
    repository: MusicRepository,
    player: PlayerController,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        YanTopBar(
            title = "我的收藏",
            subtitle = "${repository.favorites.size} 首",
            onBack = onBack,
        )
        if (repository.favorites.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Favorite,
                title = "还没有收藏",
                description = "在歌曲列表里点右侧的「更多」，即可加入收藏。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = YanSpace.screenH, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(repository.favorites, key = { _, s -> s.id }) { index, song ->
                    SongRow(
                        song = song,
                        index = index + 1,
                        isCurrent = player.current?.id == song.id,
                        isPlaying = player.isPlaying,
                        showCover = false,
                        onClick = { onPlaySong(song, repository.favorites) },
                    )
                }
            }
        }
    }
}

/** 最近播放。 */
@Composable
fun RecentScreen(
    repository: MusicRepository,
    player: PlayerController,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        YanTopBar(
            title = "最近播放",
            subtitle = "${repository.recent.size} 首",
            onBack = onBack,
        )
        if (repository.recent.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.History,
                title = "还没有播放记录",
                description = "播放任意一首歌后，会出现在这里。",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = YanSpace.screenH, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(repository.recent, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = player.current?.id == song.id,
                        isPlaying = player.isPlaying,
                        onClick = { onPlaySong(song, repository.recent) },
                    )
                }
            }
        }
    }
}
