package io.modelbridge.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * 悬浮胶囊标签栏，复刻原应用「网关管家」的自绘控件 `C.t`。
 *
 * 关键特征（均取自原版绘制代码）：
 * - 通高圆角（圆角半径 = 高度 / 2），自带 8dp 阴影，悬浮在内容之上
 * - 垂直渐变背景（近白 → 浅灰）
 * - 选中指示器是一个胶囊，位置在两个标签之间用 260ms 缓动滑过去
 */
@Composable
fun PillTabBar(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    // 原版用 PathInterpolator(0.22, 1, 0.26, 1)
    val easing = CubicBezierEasing(0.22f, 1f, 0.26f, 1f)

    // 指示器动画进度：0 = 起点，1 = 终点（原版 f223f）
    val progress = remember { Animatable(1f) }
    // 动画起点索引，用于在滑动过程中计算插值位置
    val fromIndex = remember { intArrayOf(selectedIndex) }

    // 每次选中项变化：把进度归零（起点 = 上一次的选中项），再缓动到 1。
    // 用单个 Animatable 而非 animateFloatAsState，是因为这里的"起点"会随
    // 选中项改变，需要在动画开始前同步锁定。
    LaunchedEffect(selectedIndex) {
        val target = selectedIndex
        val start = fromIndex[0]
        if (start == target) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = AppStyle.TAB_INDICATOR_DURATION_MS,
                    easing = easing,
                ),
            )
        }
        fromIndex[0] = target
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppStyle.TabBarMarginHorizontal)
            .height(AppStyle.TabBarHeight)
            .shadow(
                elevation = AppStyle.TabBarElevation,
                shape = RoundedCornerShape(AppStyle.TabBarHeight / 2),
            )
            .clip(RoundedCornerShape(AppStyle.TabBarHeight / 2))
            .background(
                Brush.verticalGradient(
                    listOf(TabBarGradientTop, TabBarGradientBottom),
                ),
            ),
    ) {
        val density = LocalDensity.current
        val barWidthPx = with(density) { maxWidth.toPx() }
        val paddingPx = with(density) { AppStyle.TabBarContentPadding.toPx() }

        // 原版 a(f2) = (宽 - 8dp*2) / 标签数
        val cellWidthPx = ((barWidthPx - paddingPx * 2f) / tabs.size).coerceAtLeast(1f)
        val cellWidthDp = with(density) { cellWidthPx.toDp() }

        val indicatorHeight = AppStyle.TabBarHeight - AppStyle.TabBarIndicatorInsetVertical
        val indicatorWidth = cellWidthDp - AppStyle.TabBarIndicatorInsetHorizontal

        // 指示器水平位置：在起点与终点之间按 progress 插值
        val from = fromIndex[0].coerceIn(0, tabs.lastIndex)
        val to = selectedIndex.coerceIn(0, tabs.lastIndex)
        val eased = progress.value
        val cellIndex = from + (to - from) * eased

        val indicatorOffset = with(density) {
            (
                (cellWidthPx - indicatorWidth.toPx()) / 2f +
                    cellIndex * cellWidthPx
                ).toDp()
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = indicatorOffset + AppStyle.TabBarContentPadding)
                .width(indicatorWidth)
                .height(indicatorHeight)
                .clip(RoundedCornerShape(indicatorHeight / 2))
                .background(TabBarIndicatorColor),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppStyle.TabBarContentPadding),
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = if (selected) Color(0xFF000000) else Color(0x99000000),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
