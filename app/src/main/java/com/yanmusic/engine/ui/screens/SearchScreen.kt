package com.yanmusic.engine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.EmptyState
import com.yanmusic.engine.ui.components.SectionHeader
import com.yanmusic.engine.ui.components.SongRow
import com.yanmusic.engine.ui.components.YanIconButton
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

/**
 * 搜索页。
 *
 * ## 搜索范围 —— 写清楚，免得看着像全域搜索
 *
 * 首版没有网络层，所以搜的是**「这个 App 里已存在的全部歌曲」**：
 * 本地导入 + 收藏 + 最近播放 + 推荐示例 + 全部歌单与榜单曲目（按 id 去重）。
 * 其中**只有本地导入的音频与内置试听曲真的能播**，其余是示例条目 ——
 * [SongRow] 会在行尾标一个「示例」小标，不让人误以为点了就能出声。
 *
 * ## 为什么首版就做真搜索，而不是留个提示
 *
 * 首页那根搜索条如果点了没反应，就是「看起来能点、其实是死的」——
 * 与本项目「示例曲目一律用虚构名」是同一条原则：**不让界面撒谎**。
 * 数据全在内存里，过滤是纯函数，成本极低，没有理由留个假的。
 *
 * ## 为什么不自动弹键盘
 *
 * 原本可以 `FocusRequester.requestFocus()` 自动聚焦，但在**没有真机可验**的
 * 前提下不采用：`requestFocus` 在节点尚未 attach 时会抛异常，而首版最不能接受的
 * 就是「一进页面就崩」。改为先展示热门词，由用户点击输入框触发键盘 ——
 * 少一点便利，换掉一整类崩溃可能。
 */
@Composable
fun SearchScreen(
    repository: MusicRepository,
    player: PlayerController,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
) {
    val palette = YanTheme.palette
    var query by remember { mutableStateOf("") }

    // ⚠️ 刻意**不用 remember 包住**：`localSongs` 是 SnapshotStateList，
    //    在 remember 里读它只会在首次求值时建立快照读取，
    //    之后新导入的曲目不会反映进来。直接算，让它正常订阅。
    val pool = buildList {
        addAll(repository.localSongs)
        addAll(repository.favorites)
        addAll(repository.recent)
        addAll(repository.recommendedSongs)
        repository.playlists.forEach { addAll(it.songs) }
        repository.rankBoards.forEach { addAll(it.songs) }
    }.distinctBy { it.id }

    val keyword = query.trim()
    val results = if (keyword.isEmpty()) {
        emptyList()
    } else {
        pool.filter { s ->
            s.title.contains(keyword, ignoreCase = true) ||
                s.artist.contains(keyword, ignoreCase = true) ||
                s.album.contains(keyword, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = query,
            onQueryChange = { query = it },
            onBack = onBack,
        )

        when {
            // ① 还没输入：给热门词 + 说明搜索范围
            keyword.isEmpty() -> SearchLanding(
                repository = repository,
                poolSize = pool.size,
                playableSize = pool.count { it.isPlayable },
                onPickKeyword = { query = it },
            )

            // ② 输入了但没命中
            results.isEmpty() -> EmptyState(
                icon = Icons.Rounded.Search,
                title = "没有找到「$keyword」",
                description = "首版只搜设备上已有的歌曲（本地导入、收藏、最近播放与内置示例）。" +
                    "要搜更多内容，需要等接入在线曲库。",
            )

            // ③ 命中
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = YanSpace.screenH,
                    end = YanSpace.screenH,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    SectionHeader(
                        title = "找到 ${results.size} 首",
                        subtitle = if (keyword.length < 1) null else "匹配「$keyword」",
                    )
                }
                items(results, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = player.current?.id == song.id,
                        isPlaying = player.isPlaying,
                        onClick = { onPlaySong(song, results) },
                    )
                }
            }
        }
    }
}

/** 顶部：返回 + 输入框 + 清空。自绘，与整体圆角风格一致（不用 Material 默认样式）。 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YanSpace.sm, vertical = YanSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YanIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = palette.textPrimary,
        )
        Spacer(Modifier.width(4.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.card)
                .border(1.dp, palette.border, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "⌕",
                color = palette.textTertiary,
                fontSize = 16.sp,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索歌曲、歌手、专辑",
                        color = palette.textTertiary,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.rowHover)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

/** 未输入时的落地区：说明搜索范围 + 热门词（直接用分类名，不另编一套假热词）。 */
@Composable
private fun SearchLanding(
    repository: MusicRepository,
    poolSize: Int,
    playableSize: Int,
    onPickKeyword: (String) -> Unit,
) {
    val palette = YanTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = YanSpace.screenH),
    ) {
        Spacer(Modifier.height(4.dp))
        SectionHeader(
            title = "热门分类",
            subtitle = "点击直接搜索",
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(repository.genres, key = { it.id }) { genre ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(palette.card)
                        .clickable { onPickKeyword(genre.name) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = genre.name,
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card)
                .padding(14.dp),
        ) {
            Column {
                Text(
                    text = "搜索范围",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前可搜 $poolSize 首（其中 $playableSize 首含音频文件，可播放）。\n" +
                        "首版没有在线曲库 —— 搜的是设备上已有的内容：本地导入、收藏、" +
                        "最近播放，以及内置的示例歌单与榜单。",
                    color = palette.textSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
