package com.slideindex.app.widget

import kotlin.math.roundToInt

object WidgetPanelLayoutMetrics {
    data class Result(
        val panelWidthPx: Int,
        /** Distance between adjacent grid dots; one span equals this width. */
        val gridStepPx: Int,
        val gridPadPx: Int,
        val viewportHeightPx: Int,
    )

    /** 一个页面在屏幕上占用的整块面板尺寸（含指示器/空态提示与上/左边距）。 */
    data class PanelBox(
        val widthPx: Int,
        val heightPx: Int,
        val topMarginPx: Int,
        /** 距屏幕左边的边距；0 表示水平居中。 */
        val leftMarginPx: Int = 0,
        /** 滚动视口最多能有多高（卡片被夹屏时会变小）。 */
        val maxViewportHeightPx: Int = Int.MAX_VALUE,
    )

    fun compute(
        screenWidthPx: Int,
        page: WidgetPanelPage,
        density: Float,
        panelPaddingDp: Float = 12f,
        panelInnerPaddingDp: Float = 4f,
        horizontalInsetDp: Float = 16f,
        leftInsetDp: Float = 0f,
    ): Result {
        val horizontalInsetPx = (horizontalInsetDp * density).roundToInt()
        val leftInsetPx = (leftInsetDp * density).roundToInt().coerceAtLeast(0)
        val panelPaddingPx = (panelPaddingDp * density).roundToInt()
        val gridPadPx = (panelInnerPaddingDp * density).roundToInt()
        val columnCount = page.columnCount.coerceAtLeast(1)
        val visibleRows = page.visibleRowCount.coerceAtLeast(1)

        val maxPanelWidthPx = (screenWidthPx - horizontalInsetPx * 2 - leftInsetPx).coerceAtLeast(1)
        val desiredCellWidthPx = if (page.cellWidthDp > 0) (page.cellWidthDp * density).roundToInt() else 0

        val (panelWidthPx, gridStepPx) = if (desiredCellWidthPx > 0) {
            val step = desiredCellWidthPx
            val panelW = step * columnCount + panelPaddingPx * 2 + gridPadPx * 2
            panelW to step
        } else {
            val innerForCellsPx = (maxPanelWidthPx - panelPaddingPx * 2 - gridPadPx * 2).coerceAtLeast(columnCount)
            val step = WidgetGridMetrics.computeGridStepPx(innerForCellsPx, columnCount)
            maxPanelWidthPx to step
        }

        val viewportInnerPx = visibleRows * gridStepPx
        val viewportHeightPx = viewportInnerPx + gridPadPx * 2

        return Result(
            panelWidthPx = panelWidthPx,
            gridStepPx = gridStepPx,
            gridPadPx = gridPadPx,
            viewportHeightPx = viewportHeightPx,
        )
    }

    /**
     * 面板的窗口尺寸。页面的列数/单元宽度/可见行数都可以逐页不同，
     * 所以切页或设置变化时必须按「当前页」重算，否则内容宽于旧尺寸就会被裁掉一列。
     */
    fun computePanelBox(
        screenWidthPx: Int,
        page: WidgetPanelPage,
        density: Float,
        pageCount: Int,
        screenHeightPx: Int = 0,
        panelPaddingDp: Float = DEFAULT_PANEL_PADDING_DP,
        panelInnerPaddingDp: Float = DEFAULT_PANEL_INNER_PADDING_DP,
        horizontalInsetDp: Float = DEFAULT_HORIZONTAL_INSET_DP,
        indicatorHeightDp: Float = DEFAULT_INDICATOR_HEIGHT_DP,
        hintHeightDp: Float = DEFAULT_HINT_HEIGHT_DP,
    ): PanelBox {
        val leftMarginPx = (page.resolvedMarginLeftDp * density).roundToInt().coerceAtLeast(0)
        val metrics = compute(
            screenWidthPx = screenWidthPx,
            page = page,
            density = density,
            panelPaddingDp = panelPaddingDp,
            panelInnerPaddingDp = panelInnerPaddingDp,
            horizontalInsetDp = horizontalInsetDp,
            leftInsetDp = page.resolvedMarginLeftDp.toFloat(),
        )
        val panelPaddingPx = (panelPaddingDp * density).roundToInt() * 2
        val indicatorHeightPx = if (pageCount > 1) (indicatorHeightDp * density).roundToInt() else 0
        val hintHeightPx = if (page.items.isEmpty()) (hintHeightDp * density).roundToInt() else 0
        return PanelBox(
            widthPx = metrics.panelWidthPx,
            heightPx = panelPaddingPx + metrics.viewportHeightPx + indicatorHeightPx + hintHeightPx,
            topMarginPx = (page.marginTopDp * density).roundToInt(),
            leftMarginPx = leftMarginPx,
        ).fitToScreen(screenHeightPx = screenHeightPx, density = density)
    }

    /**
     * 把卡片盒子夹进屏幕：高度溢出就压低高度，上下都越界就上移。
     *
     * 编辑态右下角的「添加 / 退出」按钮是卡片的底部子视图 —— 卡片超出屏幕时
     * 它们就跑到屏幕外了（真机反馈）。所以卡片本身必须始终完整可见。
     */
    private fun PanelBox.fitToScreen(screenHeightPx: Int, density: Float): PanelBox {
        if (screenHeightPx <= 0) return this
        val bottomSafePx = (12f * density).roundToInt()
        val maxHeight = (screenHeightPx - topMarginPx - bottomSafePx).coerceAtLeast(
            (120f * density).roundToInt(),
        )
        val fittedHeight = heightPx.coerceAtMost(maxHeight)
        val overlapBottom = topMarginPx + fittedHeight - (screenHeightPx - bottomSafePx)
        val fittedTop = if (overlapBottom > 0) {
            (topMarginPx - overlapBottom).coerceAtLeast((8f * density).roundToInt())
        } else {
            topMarginPx
        }
        // 滚动视口最多 = 夹完的卡高 - 固定的非视口开销（面板内边距×2 + 指示点 + 提示）。
        val fixedOverheadPx = ((DEFAULT_PANEL_PADDING_DP * 2 + DEFAULT_INDICATOR_HEIGHT_DP + DEFAULT_HINT_HEIGHT_DP) * density).roundToInt()
        val maxViewportPx = (fittedHeight - fixedOverheadPx).coerceAtLeast((80f * density).roundToInt())
        return copy(
            heightPx = fittedHeight,
            topMarginPx = fittedTop,
            maxViewportHeightPx = maxViewportPx,
        )
    }

    const val DEFAULT_PANEL_PADDING_DP = 12f
    const val DEFAULT_PANEL_INNER_PADDING_DP = 4f
    const val DEFAULT_HORIZONTAL_INSET_DP = 16f
    const val DEFAULT_INDICATOR_HEIGHT_DP = 14f
    const val DEFAULT_HINT_HEIGHT_DP = 20f
}
