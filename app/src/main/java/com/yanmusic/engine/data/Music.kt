package com.yanmusic.engine.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 音乐数据模型与本地仓库。
 *
 * ## 关于示例数据
 *
 * 首页 / 发现页里的歌曲、歌单**全部是虚构的示例内容**（曲名、歌手、专辑均为占位），
 * 不指向任何真实作品 —— 首版没有接入曲库 API，用真实作品名会造成
 * 「看起来像真的、实际播不出来」的误会，故一律用虚构名并显式标注。
 *
 * 真实可播放的内容只有两个来源：
 * 1. 用户从系统文件选择器导入的本地音频（[SongSource.LocalUri]）；
 * 2. 随包内置的试听曲（[SongSource.Asset]）。
 */

/** 歌曲来源。 */
sealed interface SongSource {
    /** 用户导入的本地文件（SAF `content://`）。播放前需解析为真实路径。 */
    data class LocalUri(val uri: String) : SongSource

    /** 随包内置的 assets 音频。首次使用会复制到应用私有目录再交给 libmpv。 */
    data class Asset(val assetPath: String) : SongSource
}

/**
 * 一首歌。
 *
 * @param durationMs 时长（毫秒）。导入时若未知填 0，UI 显示 `--:--`。
 * @param source     可播放来源；`null` 表示纯展示用的示例条目
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val source: SongSource? = null,
    /** 是否已确认高解析（≥88.2kHz）。仅本地/内置曲目在读取音频参数后可能为真。 */
    val isHiRes: Boolean = false,
) {
    val isPlayable: Boolean get() = source != null
}

/** 歌单。 */
data class Playlist(
    val id: String,
    val name: String,
    val subtitle: String,
    val playCount: Int,
    val songs: List<Song>,
)

/** 排行榜。 */
data class RankBoard(
    val id: String,
    val name: String,
    val description: String,
    val songs: List<Song>,
)

/** 发现页分类标签。 */
data class Genre(val id: String, val name: String)

// ────────────────────────────────────────────────────────────────
// 仓库
// ────────────────────────────────────────────────────────────────

/**
 * 本地数据仓库。
 *
 * 首版没有网络层，全部数据在内存里；`localSongs` / `favorites` / `recent`
 * 是可变状态列表，UI 直接观察。
 *
 * ⚠️ **未做持久化** —— 进程被杀后导入记录会丢（文件本身还在设备上）。
 * 持久化留到接 `yan-storage`（与桌面端同款 SQLite 层）时一起做。
 */
class MusicRepository {

    /** 随包内置的试听曲（24bit / 96kHz WAV，8 秒）。 */
    val builtInDemoSong: Song = Song(
        id = "builtin-hires-demo",
        title = "内置试听曲 · 24bit / 96kHz",
        artist = "YanMusic",
        album = "引擎验证",
        durationMs = 8_000L,
        source = SongSource.Asset("demo/yanmusic-hires-demo.wav"),
    )

    /**
     * 用户导入的本地音频。
     *
     * 初始含内置试听曲 —— 保证「装完就有东西可播」，
     * 首次播放时由 `LocalAudioResolver` 从 assets 释放到 cacheDir。
     * ⚠️ 声明顺序：必须在 [builtInDemoSong] 之后，否则取到未初始化的 null。
     */
    val localSongs: SnapshotStateList<Song> = mutableStateListOf(builtInDemoSong)

    /** 我的收藏。 */
    val favorites: SnapshotStateList<Song> = mutableStateListOf()

    /** 最近播放（最新的在前，最多 100 条）。 */
    val recent: SnapshotStateList<Song> = mutableStateListOf()

    fun toggleFavorite(song: Song) {
        val idx = favorites.indexOfFirst { it.id == song.id }
        if (idx >= 0) favorites.removeAt(idx) else favorites.add(0, song)
    }

    fun isFavorite(song: Song): Boolean = favorites.any { it.id == song.id }

    fun markPlayed(song: Song) {
        recent.removeAll { it.id == song.id }
        recent.add(0, song)
        while (recent.size > 100) recent.removeAt(recent.size - 1)
    }

    fun addLocalSong(song: Song) {
        if (localSongs.any { it.id == song.id }) return
        localSongs.add(song)
    }

    fun removeLocalSong(id: String) {
        localSongs.removeAll { it.id == id }
    }

    // ── 示例内容 ──

    val recommendedSongs: List<Song> = MOCK_SONGS.take(20)

    val playlists: List<Playlist> = listOf(
        // 第一张刻意是**真实可播**的：内置试听曲，点开就能出声，
        // 首屏不会出现「点什么都提示示例条目」的落差感。
        Playlist(
            "pl-builtin",
            "引擎试听",
            "真实可播 · 24bit / 96kHz",
            1_240,
            listOf(builtInDemoSong),
        ),
        Playlist("pl-01", "夜色漫游", "适合深夜独处的 40 首", 128_430, MOCK_SONGS.take(12)),
        Playlist("pl-02", "晨间清醒", "轻快电子，唤醒一天", 96_120, MOCK_SONGS.drop(4).take(14)),
        Playlist("pl-03", "旧磁带", "九十年代质感的温暖回声", 54_890, MOCK_SONGS.drop(8).take(10)),
        Playlist("pl-04", "专注书房", "无人声器乐，写代码专用", 231_770, MOCK_SONGS.drop(2).take(16)),
        Playlist("pl-05", "雨与窗", "环境音与钢琴", 88_340, MOCK_SONGS.drop(6).take(11)),
        Playlist("pl-06", "城市黄昏", "下班路上的节奏", 143_260, MOCK_SONGS.drop(10).take(13)),
        Playlist("pl-07", "远方来信", "民谣与叙事", 67_910, MOCK_SONGS.take(9)),
        Playlist("pl-08", "合成器年代", "复古 Synthwave", 175_540, MOCK_SONGS.drop(12).take(12)),
    )

    val rankBoards: List<RankBoard> = listOf(
        RankBoard("rk-01", "飙升榜", "最近 24 小时热度上升最快", MOCK_SONGS.take(10)),
        RankBoard("rk-02", "新歌榜", "本周新上架", MOCK_SONGS.drop(3).take(10)),
        RankBoard("rk-03", "热歌榜", "综合热度排行", MOCK_SONGS.drop(6).take(10)),
        RankBoard("rk-04", "原创榜", "独立创作者作品", MOCK_SONGS.drop(9).take(10)),
        RankBoard("rk-05", "怀旧榜", "十年以上的老歌", MOCK_SONGS.drop(1).take(10)),
        RankBoard("rk-06", "器乐榜", "无歌词作品", MOCK_SONGS.drop(5).take(10)),
    )

    val genres: List<Genre> = listOf(
        Genre("g-01", "流行"), Genre("g-02", "摇滚"), Genre("g-03", "民谣"),
        Genre("g-04", "电子"), Genre("g-05", "古典"), Genre("g-06", "爵士"),
        Genre("g-07", "说唱"), Genre("g-08", "轻音乐"), Genre("g-09", "古风"),
        Genre("g-10", "R&B"), Genre("g-11", "金属"), Genre("g-12", "世界音乐"),
    )

    val banners: List<Banner> = listOf(
        Banner("bn-01", "夜色漫游", "为你精选的深夜歌单", "pl-01"),
        Banner("bn-02", "高解析专区", "96kHz / 24bit 母带音质", "pl-04"),
        Banner("bn-03", "合成器年代", "复古电子精选", "pl-08"),
    )
}

/** 首页轮播横幅。 */
data class Banner(
    val id: String,
    val title: String,
    val subtitle: String,
    /** 点击后跳转的歌单 id */
    val targetPlaylistId: String,
)

// ────────────────────────────────────────────────────────────────

private const val S = 1000L

/** 虚构示例曲目。刻意不用真实作品名，避免「看起来能播、实际不能播」的误会。 */
private val MOCK_SONGS: List<Song> = listOf(
    Song("m-01", "夜航西飞", "林之遥", "《长途》", 4 * 60 * S + 12 * S),
    Song("m-02", "雾中列车", "陈默白", "《站台》", 3 * 60 * S + 48 * S),
    Song("m-03", "潮汐表", "南屿", "《海岸线》", 5 * 60 * S + 3 * S),
    Song("m-04", "空白页", "顾青禾", "《未写完》", 3 * 60 * S + 21 * S),
    Song("m-05", "旧唱片店", "周砚之", "《转述》", 4 * 60 * S + 37 * S),
    Song("m-06", "凌晨四点", "林之遥", "《长途》", 6 * 60 * S + 5 * S),
    Song("m-07", "玻璃海", "南屿", "《海岸线》", 4 * 60 * S + 28 * S),
    Song("m-08", "打字机", "陈默白", "《站台》", 3 * 60 * S + 12 * S),
    Song("m-09", "北纬四十度", "陆行舟", "《纬度》", 5 * 60 * S + 41 * S),
    Song("m-10", "雪落无声", "顾青禾", "《未写完》", 4 * 60 * S + 55 * S),
    Song("m-11", "夜行电车", "苏念安", "《环线》", 3 * 60 * S + 34 * S),
    Song("m-12", "琥珀", "周砚之", "《转述》", 4 * 60 * S + 9 * S),
    Song("m-13", "长街", "陆行舟", "《纬度》", 5 * 60 * S + 17 * S),
    Song("m-14", "候鸟", "苏念安", "《环线》", 4 * 60 * S + 44 * S),
    Song("m-15", "深蓝", "白露川", "《潜行》", 6 * 60 * S + 22 * S),
    Song("m-16", "折纸飞机", "顾青禾", "《未写完》", 3 * 60 * S + 7 * S),
    Song("m-17", "回声走廊", "陈默白", "《站台》", 5 * 60 * S + 31 * S),
    Song("m-18", "沉船信标", "白露川", "《潜行》", 4 * 60 * S + 2 * S),
    Song("m-19", "第七个夏天", "南屿", "《海岸线》", 4 * 60 * S + 19 * S),
    Song("m-20", "慢速快门", "陆行舟", "《纬度》", 5 * 60 * S + 8 * S),
    Song("m-21", "无名之辈", "苏念安", "《环线》", 3 * 60 * S + 56 * S),
    Song("m-22", "雪线之上", "白露川", "《潜行》", 6 * 60 * S + 40 * S),
)
