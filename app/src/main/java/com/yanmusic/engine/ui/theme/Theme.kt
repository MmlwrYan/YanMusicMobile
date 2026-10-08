package com.yanmusic.engine.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 移动端主题层。
 *
 * ## 设计来源
 *
 * 色板**直接取自桌面端 YanMusic**（`src/renderer/style.css` 的 CSS 变量），
 * 保证两端是同一个产品：
 *
 * | 语义 | 桌面端变量 | 暗色值 |
 * |---|---|---|
 * | 主体背景 | `--surface-main-base` | `#26262a` |
 * | 卡片 / 侧栏 | `--surface-card-base` | `#36363a` |
 * | 弹层 | `--surface-dialog-base` | `#2f2f34` |
 * | 主文字 | `--text-main` | `#f5f5f7` |
 * | 次文字 | `--text-secondary` | `#a8b0bf` |
 * | 描边 | `--border-light` | `#3a3a3d` |
 * | 主色 | `--color-primary` | `#0071e3` |
 * | 次色 | `--color-secondary` | `#5ac8fa` |
 *
 * 桌面端还有一层「主题色顶部渐变氛围」（`.layout-accent-gradient`），
 * 移动端以 [YanPalette.accentGlow] 复刻。
 *
 * ## 为什么不用 Material 默认色
 *
 * Material3 默认色板（紫）与产品识别度不符；这里把语义色做成
 * [YanPalette]，既保留桌面端 token 命名，又让组件层不必记十六进制。
 */
@Immutable
data class YanPalette(
    val isDark: Boolean,
    /** 页面主体背景 */
    val background: Color,
    /** 卡片 / 分组容器 */
    val card: Color,
    /** 抬升面（浮层、底部条、导航条） */
    val elevated: Color,
    /** 底部导航 / 迷你播放条（比 elevated 再亮一档，制造层次） */
    val chrome: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val border: Color,
    val borderStrong: Color,
    /** 主色（品牌蓝） */
    val accent: Color,
    val accentHover: Color,
    /** 主色的低透明填充，用于选中态背景 */
    val accentSoft: Color,
    /** 主色氛围渐变（页面顶部） */
    val accentGlow: Color,
    val secondary: Color,
    val danger: Color,
    val warning: Color,
    val success: Color,
    /** 列表行/hover 底色 */
    val rowHover: Color,
    /** 进度条、滑杆的轨道色 */
    val track: Color,
)

/** 暗色（默认）—— 与桌面端 `.dark` 对齐。 */
private val DarkPalette = YanPalette(
    isDark = true,
    background = Color(0xFF26262A),
    card = Color(0xFF36363A),
    elevated = Color(0xFF303034),
    chrome = Color(0xFF2F2F34),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFFA8B0BF),
    textTertiary = Color(0xFF7C828F),
    border = Color(0xFF3A3A3D),
    borderStrong = Color(0xFF4A4A50),
    accent = Color(0xFF0071E3),
    accentHover = Color(0xFF0077ED),
    accentSoft = Color(0x260071E3),
    accentGlow = Color(0x520071E3),
    secondary = Color(0xFF5AC8FA),
    danger = Color(0xFFEF4444),
    warning = Color(0xFFF59E0B),
    success = Color(0xFF10B981),
    rowHover = Color(0x0FF5F5F7),
    track = Color(0x1FF5F5F7),
)

/** 亮色 —— 与桌面端 `:root` 对齐。 */
private val LightPalette = YanPalette(
    isDark = false,
    background = Color(0xFFF5F5F7),
    card = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    chrome = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF4B5563),
    textTertiary = Color(0xFF8A8F98),
    border = Color(0xFFE5E5EA),
    borderStrong = Color(0xFFD0D0D6),
    accent = Color(0xFF0071E3),
    accentHover = Color(0xFF0077ED),
    accentSoft = Color(0x1F0071E3),
    accentGlow = Color(0x330071E3),
    secondary = Color(0xFF5AC8FA),
    danger = Color(0xFFEF4444),
    warning = Color(0xFFF59E0B),
    success = Color(0xFF10B981),
    rowHover = Color(0x0A1D1D1F),
    track = Color(0x141D1D1F),
)

val LocalYanPalette = staticCompositionLocalOf { DarkPalette }

/** 便捷读取：`YanTheme.palette.background`。 */
object YanTheme {
    val palette: YanPalette
        @Composable get() = LocalYanPalette.current
}

/**
 * 圆角与间距 token。
 *
 * 数值贴近桌面端的观感：卡片 14dp、封面 10dp、按钮全圆角。
 */
object YanShape {
    val card: RoundedCornerShape = RoundedCornerShape(14.dp)
    val cardSmall: RoundedCornerShape = RoundedCornerShape(10.dp)
    val cover: RoundedCornerShape = RoundedCornerShape(10.dp)
    val coverLarge: RoundedCornerShape = RoundedCornerShape(18.dp)
    val chip: RoundedCornerShape = RoundedCornerShape(999.dp)
    val sheet: RoundedCornerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

object YanSpace {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    /** 页面左右安全边距 */
    val screenH: Dp = 16.dp
}

@Composable
fun YanMusicTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.secondary,
            onSecondary = Color(0xFF10161C),
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.background,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.textSecondary,
            surfaceContainer = palette.card,
            surfaceContainerHigh = palette.elevated,
            outline = palette.border,
            outlineVariant = palette.border,
            error = palette.danger,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            primaryContainer = palette.accentSoft,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.secondary,
            onSecondary = Color.White,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.background,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.card,
            onSurfaceVariant = palette.textSecondary,
            surfaceContainer = palette.card,
            surfaceContainerHigh = palette.elevated,
            outline = palette.border,
            outlineVariant = palette.border,
            error = palette.danger,
            onError = Color.White,
        )
    }

    CompositionLocalProvider(LocalYanPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
