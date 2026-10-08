package com.yanmusic.engine.demo

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yanmusic.engine.data.MusicRepository
import com.yanmusic.engine.data.Song
import com.yanmusic.engine.data.SongSource
import com.yanmusic.engine.player.LocalAudioResolver
import com.yanmusic.engine.player.PlayerController
import com.yanmusic.engine.ui.AppRoot
import kotlinx.coroutines.launch

/**
 * 应用宿主。
 *
 * 职责仅四件：
 * 1. 组装依赖（仓库 / URI 解析器 / 播放控制器）；
 * 2. 注册系统文件选择器（SAF），把选中的音频导入仓库；
 * 3. 承载 [AppRoot] 与全局 Snackbar；
 * 4. 生命周期收尾（销毁引擎）。
 *
 * **界面全部在 `ui/` 包下** —— 本类不再包含任何布局代码，
 * 与首版之前的「Demo 把 UI 写在 Activity 里」相比，
 * 这样切换主题 / 复用页面都不需要碰 Activity。
 */
class MainActivity : ComponentActivity() {

    private lateinit var repository: MusicRepository
    private lateinit var resolver: LocalAudioResolver
    private lateinit var player: PlayerController

    /** setContent 里注入的导入处理器（SAF 回调晚于 onCreate，故用可空持有）。 */
    private var importHandler: ((List<Uri>) -> Unit)? = null

    /** 多选音频文件。 */
    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) importHandler?.invoke(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = MusicRepository()
        resolver = LocalAudioResolver(this)
        player = PlayerController(repository, resolver)

        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(true) }
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()

            val showMessage: (String) -> Unit = { msg ->
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(msg)
                }
            }

            // 播放层产生的一次性提示（选到示例条目、解码失败等）
            LaunchedEffect(player.message) {
                val msg = player.message
                if (msg != null) {
                    showMessage(msg)
                    player.consumeMessage()
                }
            }

            importHandler = { uris -> importAudio(uris, showMessage) }

            Box(modifier = Modifier.fillMaxSize()) {
                AppRoot(
                    repository = repository,
                    player = player,
                    darkTheme = darkTheme,
                    onToggleTheme = { darkTheme = !darkTheme },
                    onImportFiles = { pickAudio.launch(arrayOf("audio/*")) },
                    onShowMessage = showMessage,
                )
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 104.dp, start = 12.dp, end = 12.dp),
                )
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    /** 把 SAF 选中的 URI 登记进仓库。 */
    private fun importAudio(uris: List<Uri>, showMessage: (String) -> Unit) {
        var added = 0
        uris.forEach { uri ->
            val display = queryDisplayName(uri) ?: uri.lastPathSegment ?: "未知文件"
            val ext = display.substringAfterLast('.', "").uppercase()
            val song = Song(
                id = uri.toString(),
                title = display.substringBeforeLast('.'),
                artist = "本地文件",
                album = if (ext.isNotEmpty()) ext else "音频",
                source = SongSource.LocalUri(uri.toString()),
            )
            val before = repository.localSongs.size
            repository.addLocalSong(song)
            if (repository.localSongs.size > before) added++
            persistPermission(uri)
        }
        showMessage(
            if (added > 0) "已导入 $added 首，可在「音乐库 · 本地音乐」里播放"
            else "这些文件已经在音乐库里了"
        )
    }

    /** 读 SAF 的显示名（用于标题）。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    /**
     * 申请持久化读权限。
     *
     * 不申请的话，进程重启后原 URI 会失效 —— 表现为「列表里还在，点了播不出」。
     * 部分 Provider（如某些网盘）不支持持久化，失败可忽略。
     */
    private fun persistPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
