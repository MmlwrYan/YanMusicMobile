package com.yanmusic.engine.player

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.yanmusic.engine.data.SongSource
import java.io.File

/**
 * 把 [SongSource] 解析成 **libmpv 能直接打开的真实文件路径**。
 *
 * ## 为什么需要这一层
 *
 * `yan-engine-core` 的 `load(uri)` 实际执行的是 mpv 的 `loadfile <uri>`。
 * libmpv **不认识 Android 的 `content://` URI**（那是 ContentProvider 抽象，
 * 需要 ContentResolver 才能解），它只接受：
 *
 * - 真实文件系统路径（`/data/.../x.flac`）
 * - 它能自行处理的协议 URL（`http://`、`file://` …）
 *
 * 所以 SAF 选出来的 `content://` 必须转换。这里用 **`/proc/self/fd/<n>`**：
 *
 * 1. `ContentResolver.openFileDescriptor(uri, "r")` 拿到 fd；
 * 2. 把 `/proc/self/fd/<fd>` 当作路径交给 mpv —— Linux 下 `open()` 这个路径
 *    会重新打开 fd 指向的同一文件（可 seek），因此 mpv 能正常读。
 * 3. **必须保持该 fd 打开直到播放结束**（本类持有并负责关闭）。
 *
 * 相比「导入时把文件复制进应用目录」，这种方式不产生副本、不占额外空间，
 * 对动辄上百 MB 的无损文件更友好。
 *
 * ⚠️ 已知限制：若某 Provider 返回的是**管道/流**（不可 seek），
 * mpv 的 seek 会失败 —— 表现为进度条拖不动。届时需退回复制方案。
 */
class LocalAudioResolver(private val context: Context) {

    private var openDescriptor: ParcelFileDescriptor? = null
    private val assetCacheDir: File by lazy { File(context.cacheDir, "media").apply { mkdirs() } }

    /**
     * 解析为可交给 mpv 的路径。
     *
     * @return 成功返回路径；失败返回 null（调用方负责提示）
     */
    fun resolve(source: SongSource): String? = when (source) {
        is SongSource.LocalUri -> resolveContentUri(source.uri)
        is SongSource.Asset -> resolveAsset(source.assetPath)
    }

    private fun resolveContentUri(uri: String): String? = try {
        val pfd = context.contentResolver.openFileDescriptor(
            android.net.Uri.parse(uri),
            "r",
        )
        if (pfd == null) {
            Log.w(TAG, "openFileDescriptor 返回 null：$uri")
            null
        } else {
            closeQuietly()
            openDescriptor = pfd
            "/proc/self/fd/${pfd.fd}"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "解析 content:// 失败：$uri", t)
        null
    }

    /**
     * 内置 assets 音频：**必须先落到真实文件**。
     *
     * assets 打包在 APK 内部，没有可直接 open 的路径；mpv 打不开 `asset://`。
     * 这里在首次使用时复制到 `cacheDir/media/`，之后直接复用。
     */
    private fun resolveAsset(assetPath: String): String? = try {
        val out = File(assetCacheDir, File(assetPath).name)
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        out.absolutePath
    } catch (t: Throwable) {
        Log.w(TAG, "释放内置音频失败：$assetPath", t)
        null
    }

    /** 停止播放时调用，释放可能占用的 fd。 */
    fun release() = closeQuietly()

    private fun closeQuietly() {
        try {
            openDescriptor?.close()
        } catch (_: Throwable) {
            // 已关闭 / 已被 mpv 接管，忽略
        }
        openDescriptor = null
    }

    private companion object {
        const val TAG = "LocalAudioResolver"
    }
}
