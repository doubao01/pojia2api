package io.modelbridge.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面常量，数值取自原应用「网关管家」3.10.0 的绘制代码。
 *
 * 原版的界面是 Java 手工拼 View + 自绘标签栏，尺寸与配色散落在
 * `MainActivity.onCreate` 和 `C.t`（标签栏控件）里。这里把那些魔法数字
 * 集中到一处，便于对照与调整；注释里保留了原始取值。
 */
object AppStyle {

    // ---------- 颜色 ----------

    /** 页面背景，原版 `-855562`。 */
    val PageBackground = Color(0xFFF2F1F6)

    /** 标题文字，原版 `-16777216`。 */
    val TitleText = Color(0xFF000000)

    /** 次要文字 / 副标题，原版 `-1728053248`（黑色 60%）。 */
    val SubtitleText = Color(0x99000000)

    /** 卡片背景。 */
    val CardBackground = Color.White

    /** 强调色，原版 `-1750963`。 */
    val Accent = Color(0xFFE5484D)

    /** 正文字色。 */
    val BodyText = Color(0xFF1A1A1A)

    /** 分隔线。 */
    val Divider = Color(0x14000000)

    // ---------- 标题栏 ----------
    // 原版 padding: left 20, top 18, right 20, bottom 10

    val TitleBarPaddingHorizontal = 20.dp
    val TitleBarPaddingTop = 18.dp
    val TitleBarPaddingBottom = 10.dp

    /** 「网关管家」26sp 加粗。 */
    val TitleFontSize = 26.sp

    /** 副标题 13sp 常规，padding-top 5。 */
    val SubtitleFontSize = 13.sp
    val SubtitlePaddingTop = 5.dp

    // ---------- 悬浮标签栏 ----------
    // 原版 FrameLayout.LayoutParams: height 62dp, 左右 margin 22dp,
    // bottom margin 20dp, gravity bottom；阴影 elevation 8dp。

    val TabBarHeight = 62.dp
    val TabBarMarginHorizontal = 22.dp
    val TabBarMarginBottom = 20.dp
    val TabBarElevation = 8.dp

    /** 指示器胶囊：高度 = 栏高 - 9dp，宽度 = 单元宽 - 6dp。 */
    val TabBarIndicatorInsetVertical = 9.dp
    val TabBarIndicatorInsetHorizontal = 6.dp

    /** 标签栏左右内边距，原版 `a(f2)` 里的 `8dp * 2`。 */
    val TabBarContentPadding = 8.dp

    /** 切换指示器动画：260ms，PathInterpolator(0.22, 1, 0.26, 1)。 */
    const val TAB_INDICATOR_DURATION_MS = 260

    /** 切页动画：190ms 淡入 + 位移 20dp。 */
    const val PAGE_TRANSITION_DURATION_MS = 190
    val PageTransitionOffset = 20.dp

    // ---------- 内容区 ----------

    val ContentPadding = 16.dp
    val CardSpacing = 8.dp
    val CardPaddingHorizontal = 16.dp
    val CardPaddingVertical = 14.dp

    /** 标签栏悬浮在内容之上，列表底部要留出避让空间。 */
    val ContentBottomInset = 96.dp
}

/**
 * 标签栏渐变背景：上 `-134217729`、下 `-319424776`（近似白色 → 浅灰）。
 */
val TabBarGradientTop = Color(0xF7FFFFFF)
val TabBarGradientBottom = Color(0xECF5F6F8)

/** 指示器底色，原版 `335544320`（黑色 8%）。 */
val TabBarIndicatorColor = Color(0x14000000)
