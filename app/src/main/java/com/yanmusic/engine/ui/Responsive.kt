package com.yanmusic.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口尺寸档位。
 *
 * 阈值沿用 Material 3 的 window size class 定义
 * （compact < 600dp ≤ medium < 840dp ≤ expanded），
 * 但**不引入 `material3-window-size-class` 依赖** ——
 * 该库在我们离线构建环境里没有缓存，而需求只需一个宽度判断。
 *
 * | 档位 | 典型设备 | 导航形态 | 内容列数 |
 * |---|---|---|---|
 * | [Compact] | 手机竖屏 | 底部导航栏 | 1–2 |
 * | [Medium] | 手机横屏 / 小折叠展开 / 小平板 | 侧边导航轨（仅图标） | 2–3 |
 * | [Expanded] | 平板 / 桌面模式 | 侧边导航轨（图标+文字） | 3–5 |
 */
enum class WindowClass { Compact, Medium, Expanded }

/** 当前窗口的布局规格。由 [rememberLayoutSpec] 计算，随屏幕尺寸变化自动重组。 */
data class LayoutSpec(
    val windowClass: WindowClass,
    /** 是否用侧边导航轨替代底部导航栏 */
    val useNavRail: Boolean,
    /** 导航轨是否展开文字（Expanded 档才展开） */
    val railExpandLabels: Boolean,
    /** 内容区最大宽度；超宽屏时居中留白，避免长行难读 */
    val contentMaxWidth: Dp,
    /** 是否横屏 */
    val isLandscape: Boolean,
) {
    /** 宽屏下「列表 + 详情」双栏是否可行（平板/桌面模式） */
    val supportsTwoPane: Boolean get() = windowClass == WindowClass.Expanded
}

/**
 * 读取当前布局规格。
 *
 * ⚠️ 依赖 [LocalConfiguration] 的 `screenWidthDp` ——
 * Activity 已声明 `configChanges="orientation|screenSize|keyboardHidden"`，
 * 因此旋转/分屏时**不会重建 Activity**，但 Configuration 变化会触发重组，
 * 这里的 `remember(key)` 会立即拿到新值。
 */
@Composable
fun rememberLayoutSpec(): LayoutSpec {
    val cfg = LocalConfiguration.current
    val width = cfg.screenWidthDp.dp
    val height = cfg.screenHeightDp.dp

    return remember(width, height) {
        val windowClass = when {
            width < 600.dp -> WindowClass.Compact
            width < 840.dp -> WindowClass.Medium
            else -> WindowClass.Expanded
        }
        LayoutSpec(
            windowClass = windowClass,
            useNavRail = windowClass != WindowClass.Compact,
            railExpandLabels = windowClass == WindowClass.Expanded,
            contentMaxWidth = when (windowClass) {
                WindowClass.Compact -> Dp.Unspecified
                WindowClass.Medium -> 720.dp
                WindowClass.Expanded -> 1080.dp
            },
            isLandscape = width > height,
        )
    }
}
