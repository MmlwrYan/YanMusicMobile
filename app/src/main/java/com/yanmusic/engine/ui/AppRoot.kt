package com.yanmusic.engine.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yanmusic.engine.data.Banner
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Playlist
import com.yanmusic.engine.data.RankBoard
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.components.MiniPlayerBar
import com.yanmusic.engine.ui.screens.DiagnosticsScreen
import com.yanmusic.engine.ui.screens.DiscoverScreen
import com.yanmusic.engine.ui.screens.FavoritesScreen
import com.yanmusic.engine.ui.screens.HomeScreen
import com.yanmusic.engine.ui.screens.LibraryScreen
import com.yanmusic.engine.ui.screens.PlayerScreen
import com.yanmusic.engine.ui.screens.PlaylistDetailScreen
import com.yanmusic.engine.ui.screens.ProfileScreen
import com.yanmusic.engine.ui.screens.RecentScreen
import com.yanmusic.engine.ui.screens.SettingsScreen
import com.yanmusic.engine.ui.theme.YanMusicTheme
import com.yanmusic.engine.ui.theme.YanSpace
import com.yanmusic.engine.ui.theme.YanTheme

// ────────────────────────────────────────────────────────────────
// 路由
// ────────────────────────────────────────────────────────────────

/** 一级导航（底部导航栏 / 侧边导航轨）。 */
enum class RootTab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Rounded.Home),
    Discover("发现", Icons.Rounded.Explore),
    Library("音乐库", Icons.Rounded.LibraryMusic),
    Profile("我的", Icons.Rounded.Person),
}

/** 页面。一级 Tab 与压栈详情用同一套类型，栈底永远是某个 [RootTab]。 */
sealed interface Screen {
    data class Tab(val tab: RootTab) : Screen
    data object Player : Screen
    data class PlaylistDetail(val playlistId: String, val title: String, val songs: List<Song>) : Screen
    data class RankDetail(val boardId: String, val title: String, val songs: List<Song>) : Screen
    data object Favorites : Screen
    data object Recent : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
}

/**
 * 应用根：导航骨架 + 内容区 + 迷你播放条。
 *
 * ## 响应式规则（对应 [LayoutSpec]）
 *
 * | 档位 | 导航 | 内容 |
 * |---|---|---|
 * | Compact | 底部导航栏 | 全宽，网格自适应 2 列起 |
 * | Medium | 左侧导航轨（仅图标） | 内容最大 720dp 居中 |
 * | Expanded | 左侧导航轨（图标+文字） | 内容最大 1080dp 居中 |
 *
 * 详情页与播放页会隐藏一级导航（主流音乐软件的一致做法），
 * 迷你播放条在详情页保留，进全屏播放页时隐藏。
 */
@Composable
fun AppRoot(
    repository: MusicRepository,
    player: PlayerController,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onImportFiles: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    YanMusicTheme(darkTheme = darkTheme) {
        val spec = rememberLayoutSpec()
        val palette = YanTheme.palette

        val stack = remember { mutableStateListOf<Screen>(Screen.Tab(RootTab.Home)) }
        val current = stack.last()
        val activeTab = (stack.first() as? Screen.Tab)?.tab ?: RootTab.Home
        val isTabScreen = current is Screen.Tab
        val isPlayerScreen = current is Screen.Player

        fun navigateToTab(tab: RootTab) {
            if (stack.size == 1 && activeTab == tab) return
            stack.clear()
            stack.add(Screen.Tab(tab))
        }

        fun open(screen: Screen) {
            stack.add(screen)
        }

        fun back() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }

        // 系统返回键：优先出栈，栈底时交还系统
        BackHandler(enabled = stack.size > 1) { back() }

        // 进度拉取循环：高频属性（time-pos / duration）走「拉」不走事件。
        // 250ms 一次——够进度条与迷你条平滑，又不会给事件循环压力。
        LaunchedEffect(player.engineState) {
            while (true) {
                player.tick()
                kotlinx.coroutines.delay(250L)
            }
        }

        // 播放控制回调
        val onPlaySong: (Song, List<Song>?) -> Unit = { song, list ->
            player.play(song, list)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    if (spec.useNavRail && !isPlayerScreen) {
                        YanNavRail(
                            active = activeTab,
                            expanded = spec.railExpandLabels,
                            onSelect = { navigateToTab(it) },
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // 内容区：宽屏时限制最大宽度并居中，避免长行难读
                        val contentBoxModifier = if (spec.contentMaxWidth == Dp.Unspecified) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .fillMaxHeight()
                                .widthIn(max = spec.contentMaxWidth)
                                .fillMaxWidth()
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(modifier = contentBoxModifier) {
                                ScreenContent(
                                    screen = current,
                                    spec = spec,
                                    repository = repository,
                                    player = player,
                                    onOpen = { open(it) },
                                    onBack = { back() },
                                    onPlaySong = onPlaySong,
                                    onImportFiles = onImportFiles,
                                    onToggleTheme = onToggleTheme,
                                    darkTheme = darkTheme,
                                    onShowMessage = onShowMessage,
                                )
                            }
                        }

                        // 顶部主题色氛围层（与桌面端 `.layout-accent-gradient` 对应）
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(240.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            palette.accentGlow.copy(alpha = 0.30f),
                                            palette.accentGlow.copy(alpha = 0.08f),
                                            Color.Transparent,
                                        ),
                                    )
                                ),
                        )
                    }
                }

                if (!isPlayerScreen) {
                    if (player.hasCurrent) {
                        MiniPlayerBar(
                            song = player.current!!,
                            progress = player.progress,
                            isPlaying = player.isPlaying,
                            isHiRes = player.audioParams?.isHiRes == true,
                            onTap = { open(Screen.Player) },
                            onTogglePlay = { player.togglePause() },
                            onNext = { player.next() },
                        )
                    }
                    if (!spec.useNavRail && isTabScreen) {
                        YanBottomNav(
                            active = activeTab,
                            onSelect = { navigateToTab(it) },
                        )
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────
// 内容分发
// ────────────────────────────────────────────────────────────────

@Composable
private fun ScreenContent(
    screen: Screen,
    spec: LayoutSpec,
    repository: MusicRepository,
    player: PlayerController,
    onOpen: (Screen) -> Unit,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>?) -> Unit,
    onImportFiles: () -> Unit,
    onToggleTheme: () -> Unit,
    darkTheme: Boolean,
    onShowMessage: (String) -> Unit,
) {
    when (screen) {
        is Screen.Tab -> when (screen.tab) {
            RootTab.Home -> HomeScreen(
                repository = repository,
                player = player,
                spec = spec,
                onOpenPlaylist = { pl -> onOpen(Screen.PlaylistDetail(pl.id, pl.name, pl.songs)) },
                onOpenBanner = { banner -> openBanner(banner, repository, onOpen) },
                onPlaySong = onPlaySong,
                onSeeAllRecommended = {
                    onOpen(Screen.PlaylistDetail("recommend", "每日推荐", repository.recommendedSongs))
                },
            )

            RootTab.Discover -> DiscoverScreen(
                repository = repository,
                onOpenRank = { b -> onOpen(Screen.RankDetail(b.id, b.name, b.songs)) },
                onOpenPlaylist = { pl -> onOpen(Screen.PlaylistDetail(pl.id, pl.name, pl.songs)) },
            )

            RootTab.Library -> LibraryScreen(
                repository = repository,
                player = player,
                onImportFiles = onImportFiles,
                onOpenFavorites = { onOpen(Screen.Favorites) },
                onOpenRecent = { onOpen(Screen.Recent) },
                onPlaySong = onPlaySong,
            )

            RootTab.Profile -> ProfileScreen(
                repository = repository,
                player = player,
                darkTheme = darkTheme,
                onOpenSettings = { onOpen(Screen.Settings) },
                onOpenFavorites = { onOpen(Screen.Favorites) },
                onOpenRecent = { onOpen(Screen.Recent) },
            )
        }

        Screen.Player -> PlayerScreen(
            player = player,
            spec = spec,
            onBack = onBack,
            repository = repository,
        )

        is Screen.PlaylistDetail -> PlaylistDetailScreen(
            title = screen.title,
            songs = screen.songs,
            playlistSeed = screen.playlistId,
            player = player,
            repository = repository,
            onBack = onBack,
            onPlaySong = onPlaySong,
            onShowMessage = onShowMessage,
        )

        is Screen.RankDetail -> PlaylistDetailScreen(
            title = screen.title,
            songs = screen.songs,
            playlistSeed = screen.boardId,
            player = player,
            repository = repository,
            onBack = onBack,
            onPlaySong = onPlaySong,
            onShowMessage = onShowMessage,
        )

        Screen.Favorites -> FavoritesScreen(
            repository = repository,
            player = player,
            onBack = onBack,
            onPlaySong = onPlaySong,
        )

        Screen.Recent -> RecentScreen(
            repository = repository,
            player = player,
            onBack = onBack,
            onPlaySong = onPlaySong,
        )

        Screen.Settings -> SettingsScreen(
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme,
            onBack = onBack,
            onOpenDiagnostics = { onOpen(Screen.Diagnostics) },
            player = player,
        )

        Screen.Diagnostics -> DiagnosticsScreen(player = player, onBack = onBack)
    }
}

private fun openBanner(banner: Banner, repository: MusicRepository, onOpen: (Screen) -> Unit) {
    val pl = repository.playlists.firstOrNull { it.id == banner.targetPlaylistId }
    if (pl != null) onOpen(Screen.PlaylistDetail(pl.id, pl.name, pl.songs))
}

// ────────────────────────────────────────────────────────────────
// 导航组件（自绘，完全对齐桌面端观感）
// ────────────────────────────────────────────────────────────────

@Composable
private fun YanBottomNav(
    active: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    val palette = YanTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.chrome)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RootTab.entries.forEach { tab ->
            val selected = tab == active
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) palette.accent else palette.textTertiary,
                    modifier = Modifier.size(23.dp),
                )
                Text(
                    text = tab.label,
                    color = if (selected) palette.accent else palette.textTertiary,
                    fontSize = 10.5.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun YanNavRail(
    active: RootTab,
    expanded: Boolean,
    onSelect: (RootTab) -> Unit,
) {
    val palette = YanTheme.palette
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (expanded) 168.dp else 78.dp)
            .background(palette.chrome)
            .padding(vertical = YanSpace.md, horizontal = YanSpace.sm),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (expanded) "YanMusic" else "Yan",
            color = palette.accent,
            fontSize = if (expanded) 17.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        RootTab.entries.forEach { tab ->
            val selected = tab == active
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) palette.accentSoft else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(
                        horizontal = if (expanded) 12.dp else 0.dp,
                        vertical = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) palette.accent else palette.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
                if (expanded) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = tab.label,
                        color = if (selected) palette.accent else palette.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
