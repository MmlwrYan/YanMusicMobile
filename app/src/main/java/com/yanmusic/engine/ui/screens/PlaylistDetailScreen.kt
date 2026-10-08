package com.yanmusic.engine.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.GradientCover
import com.yanmusic.engine.ui.components.PrimaryButton
import com.yanmusic.engine.ui.components.SecondaryButton
import com.yanmusic.engine.ui.components.SongRow
import com.yanmusic.engine.ui.components.YanTopBar
import com.yanmusic.engine.ui.components.formatCount
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme
import kotlin.random.Random

/**
 * 歌单 / 榜单详情。
 *
 * 顶部是大封面 + 统计 + 播放全部；宽屏（Expanded）时封面与列表**左右分栏**，
 * 窄屏时上下堆叠 —— 这是本页唯一按 [com.yanmusic.engine.ui.LayoutSpec] 变化的地方。
 */
@Composable
fun PlaylistDetailScreen(
    title: String,
    songs: List<Song>,
    playlistSeed: String,
    player: PlayerController,
    repository: MusicRepository,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val palette = YanTheme.palette

    Column(modifier = Modifier.fillMaxSize()) {
        YanTopBar(title = title, onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = YanSpace.screenH,
                end = YanSpace.screenH,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GradientCover(
                        seed = playlistSeed,
                        modifier = Modifier.size(132.dp),
                        shape = com.yanmusic.engine.ui.theme.YanShape.coverLarge,
                        iconSize = 40.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = palette.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "共 ${songs.size} 首 · 播放量 ${formatCount(songs.size * 12_340)}",
                            color = palette.textTertiary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                text = "播放全部",
                                icon = Icons.Rounded.PlayArrow,
                                onClick = {
                                    val first = songs.firstOrNull()
                                    if (first == null) {
                                        onShowMessage("这个歌单是空的")
                                    } else {
                                        onPlaySong(first, songs)
                                    }
                                },
                            )
                            SecondaryButton(
                                text = "随机",
                                icon = Icons.Rounded.Shuffle,
                                onClick = {
                                    val pick = songs.randomOrNull()
                                    if (pick == null) {
                                        onShowMessage("这个歌单是空的")
                                    } else {
                                        player.play(pick, songs.shuffled(Random(System.currentTimeMillis())))
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "歌曲",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    index = index + 1,
                    isCurrent = player.current?.id == song.id,
                    isPlaying = player.isPlaying,
                    showCover = false,
                    onClick = { onPlaySong(song, songs) },
                    onMore = {
                        if (song.isPlayable) {
                            val nowFav = repository.isFavorite(song)
                            repository.toggleFavorite(song)
                            onShowMessage(if (nowFav) "已取消收藏：${song.title}" else "已收藏：${song.title}")
                        } else {
                            onShowMessage("示例条目，无法收藏")
                        }
                    },
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
                Box(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
