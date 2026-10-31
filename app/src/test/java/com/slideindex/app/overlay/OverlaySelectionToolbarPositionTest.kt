package com.slideindex.app.overlay

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySelectionToolbarPositionTest {

    /**
     * 点词关闭的取词面板：视口=正文区、全选后选区铺满整个视口。
     * 旧逻辑在视口里无解，只能把工具条压回文字上；现在允许探出视口。
     */
    @Test
    fun selectAllCoveringViewport_toolbarFallsOutsideSelection() {
        val viewport = Rect(0f, 800f, 1080f, 1400f)
        val position = positionFor(
            anchor = Rect(0f, 800f, 1080f, 860f),
            selection = viewport,
            viewport = viewport,
        )
        assertTrue(
            "工具条仍压住全选文字：$position",
            position.y + TOOLBAR_HEIGHT + GAP_PX <= 800 || position.y - GAP_PX >= 1400,
        )
    }

    /** 普通局部选择仍应留在正文视口内（不要把上一处修复用成"到处乱飘"）。 */
    @Test
    fun partialSelection_staysInsideViewport() {
        val viewport = Rect(0f, 800f, 1080f, 1400f)
        val selection = Rect(0f, 900f, 1080f, 1000f)
        val position = positionFor(
            anchor = Rect(0f, 900f, 1080f, 960f),
            selection = selection,
            viewport = viewport,
        )
        assertTrue("工具条跑出正文视口：$position", position.y >= 800)
        assertTrue("工具条跑出正文视口：$position", position.y + TOOLBAR_HEIGHT <= 1400)
        assertTrue(
            "工具条压住选中文字：$position",
            position.y - GAP_PX >= selection.bottom || position.y + TOOLBAR_HEIGHT + GAP_PX <= selection.top,
        )
    }

    private fun positionFor(anchor: Rect, selection: Rect?, viewport: Rect): IntOffset {
        val provider = OverlaySelectionToolbarPositionProvider(
            placementProvider = {
                OverlaySelectionToolbarPlacement(
                    anchorRect = anchor,
                    selectionRect = selection,
                    viewportRect = viewport,
                )
            },
            gapPx = GAP_PX,
        )
        return provider.calculatePosition(
            anchorBounds = IntRect.Zero,
            windowSize = IntSize(WINDOW_WIDTH, WINDOW_HEIGHT),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(TOOLBAR_WIDTH, TOOLBAR_HEIGHT),
        )
    }

    private companion object {
        const val WINDOW_WIDTH = 1080
        const val WINDOW_HEIGHT = 2400
        const val TOOLBAR_WIDTH = 480
        const val TOOLBAR_HEIGHT = 132
        const val GAP_PX = 24
    }
}
