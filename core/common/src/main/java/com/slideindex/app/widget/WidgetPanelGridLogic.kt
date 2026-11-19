package com.slideindex.app.widget

object WidgetPanelGridLogic {
    fun computeContentRowCount(page: WidgetPanelPage): Int {
        val maxOccupiedY = page.items.maxOfOrNull { it.y + it.spanY } ?: 0
        return maxOf(page.visibleRowCount, maxOccupiedY)
    }

    fun computeEditRowCount(page: WidgetPanelPage, bufferRows: Int = 5): Int {
        return computeContentRowCount(page) + bufferRows
    }

    fun isAreaFree(
        page: WidgetPanelPage,
        x: Int,
        y: Int,
        spanX: Int,
        spanY: Int,
        ignoreWidgetId: Int? = null,
        maxRowLimit: Int? = null,
    ): Boolean {
        if (x < 0 || y < 0 || x + spanX > page.columnCount) {
            return false
        }
        if (maxRowLimit != null && y + spanY > maxRowLimit) {
            return false
        }
        for (item in page.items) {
            if (item.appWidgetId == ignoreWidgetId) continue
            if (rectsOverlap(x, y, spanX, spanY, item.x, item.y, item.spanX, item.spanY)) {
                return false
            }
        }
        return true
    }

    fun findFirstFreeSlot(page: WidgetPanelPage, spanX: Int, spanY: Int): Pair<Int, Int>? {
        if (spanX > page.columnCount) return null
        val contentBottom = page.items.maxOfOrNull { it.y + it.spanY } ?: 0
        for (y in 0 until contentBottom) {
            for (x in 0..(page.columnCount - spanX)) {
                if (isAreaFree(page, x, y, spanX, spanY)) return x to y
            }
        }
        return 0 to contentBottom
    }

    fun removeItem(page: WidgetPanelPage, appWidgetId: Int): WidgetPanelPage {
        val without = page.items.filterNot { it.appWidgetId == appWidgetId }
        val updated = page.copy(items = without)
        return updated.copy(rowCount = computeContentRowCount(updated))
    }

    fun upsertItem(page: WidgetPanelPage, item: WidgetPanelItem): WidgetPanelPage {
        val without = page.items.filterNot { it.appWidgetId == item.appWidgetId }
        val updated = page.copy(items = without + item)
        return updated.copy(rowCount = computeContentRowCount(updated))
    }

    fun fitItemToGrid(page: WidgetPanelPage, item: WidgetPanelItem): WidgetPanelItem {
        val spanX = item.spanX.coerceIn(1, page.columnCount)
        val spanY = item.spanY.coerceAtLeast(1)
        val x = item.x.coerceIn(0, (page.columnCount - spanX).coerceAtLeast(0))
        val y = item.y.coerceAtLeast(0)
        return item.copy(x = x, y = y, spanX = spanX, spanY = spanY)
    }

    /**
     * 把一页裁进网格范围。
     *
     * ⚠️ 只 clamp 会**重叠**：列数变少时（4 → 3），原本在 x=3 的格子被压到 x=2，
     * 和本来就在 x=2 的格子撞在一起 → 真机上的"格子互相重叠"。
     * 所以 clamp 之后还要按顺序**去重叠**：已被占住的就顺延到下一个空位。
     */
    fun fitPageToGrid(page: WidgetPanelPage): WidgetPanelPage {
        if (page.items.isEmpty()) return page.copy(rowCount = computeContentRowCount(page))
        val clamped = page.items.map { fitItemToGrid(page, it) }
        val placed = mutableListOf<WidgetPanelItem>()
        for (item in clamped) {
            var candidate = item
            // 换个空位（行优先）直到不重叠；用来判断空位的临时页只包含已摆放的格子。
            val occupied = page.copy(items = placed)
            if (!isAreaFree(occupied, candidate.x, candidate.y, candidate.spanX, candidate.spanY)) {
                val free = findFirstFreeSlot(occupied, candidate.spanX, candidate.spanY)
                if (free != null) {
                    candidate = candidate.copy(x = free.first, y = free.second)
                }
            }
            placed += candidate
        }
        val updated = page.copy(items = placed)
        return updated.copy(rowCount = computeContentRowCount(updated))
    }

    /**
     * 拖动**插入**（不是交换）：目标位置被别的格子占着时，把挡路的格子往后挤，
     * 给被拖的格子腾位置 —— 和列表拖拽重排的手感一致。
     *
     * 旧实现 [moveItemWithAutoSwapOrShift] 是"1 换 1 交换"：拖到别人身上两个格子互换，
     * 用户要的是"插入"（其余格子依次后移）。
     */
    fun moveItemInsert(
        page: WidgetPanelPage,
        targetItem: WidgetPanelItem,
        targetX: Int,
        targetY: Int,
    ): WidgetPanelPage {
        val clampedX = targetX.coerceIn(0, (page.columnCount - targetItem.spanX).coerceAtLeast(0))
        val clampedY = targetY.coerceAtLeast(0)
        val moved = targetItem.copy(x = clampedX, y = clampedY)
        // 1) 先移除被拖的格子，余下的按阅读顺序（y 再 x）排列 —— 插入顺序要稳定。
        val others = page.items
            .filterNot { it.appWidgetId == targetItem.appWidgetId }
            .sortedWith(compareBy({ it.y }, { it.x }))
        // 2) 依次摆放：先放在插入点之前的格子，再放被拖的格子，最后放其余格子；
        //    任何与已摆放格子重叠的，顺延到下一个空位（= "插入 + 其余后移"）。
        val placed = mutableListOf<WidgetPanelItem>()
        var movedPlaced = false
        for (other in others) {
            val afterInsertPoint = other.y > moved.y || (other.y == moved.y && other.x >= moved.x)
            if (!movedPlaced && afterInsertPoint) {
                placed += moved
                movedPlaced = true
            }
            var candidate = other
            val placedPage = page.copy(items = placed)
            if (!isAreaFree(placedPage, candidate.x, candidate.y, candidate.spanX, candidate.spanY)) {
                val free = findFirstFreeSlot(placedPage, candidate.spanX, candidate.spanY)
                if (free != null) candidate = candidate.copy(x = free.first, y = free.second)
            }
            placed += candidate
        }
        if (!movedPlaced) placed += moved
        val updated = page.copy(items = placed)
        return updated.copy(rowCount = computeContentRowCount(updated))
    }

    fun moveItemWithAutoSwapOrShift(
        page: WidgetPanelPage,
        targetItem: WidgetPanelItem,
        targetX: Int,
        targetY: Int,
    ): WidgetPanelPage {
        val clampedX = targetX.coerceIn(0, (page.columnCount - targetItem.spanX).coerceAtLeast(0))
        val clampedY = targetY.coerceAtLeast(0)

        if (isAreaFree(page, clampedX, clampedY, targetItem.spanX, targetItem.spanY, targetItem.appWidgetId)) {
            return upsertItem(page, targetItem.copy(x = clampedX, y = clampedY))
        }

        // Find overlapping items
        val overlapping = page.items.filter {
            it.appWidgetId != targetItem.appWidgetId &&
            rectsOverlap(clampedX, clampedY, targetItem.spanX, targetItem.spanY, it.x, it.y, it.spanX, it.spanY)
        }

        // 1-to-1 exact swap if single overlap matches span
        if (overlapping.size == 1) {
            val other = overlapping.first()
            if (other.spanX == targetItem.spanX && other.spanY == targetItem.spanY) {
                val newTarget = targetItem.copy(x = clampedX, y = clampedY)
                val newOther = other.copy(x = targetItem.x, y = targetItem.y)
                val others = page.items.filterNot { it.appWidgetId == targetItem.appWidgetId || it.appWidgetId == other.appWidgetId }
                return page.copy(items = others + newTarget + newOther)
            }
        }

        // General push / shift to next free slot
        val newTarget = targetItem.copy(x = clampedX, y = clampedY)
        var tempPage = page.copy(items = page.items.filterNot { it.appWidgetId == targetItem.appWidgetId } + newTarget)
        val remainingItems = tempPage.items.toMutableList()
        for (overlap in overlapping) {
            val pageWithoutOverlap = tempPage.copy(items = remainingItems.filterNot { it.appWidgetId == overlap.appWidgetId })
            val freeSlot = findFirstFreeSlot(pageWithoutOverlap, overlap.spanX, overlap.spanY)
            if (freeSlot != null) {
                val shifted = overlap.copy(x = freeSlot.first, y = freeSlot.second)
                remainingItems.removeAll { it.appWidgetId == overlap.appWidgetId }
                remainingItems.add(shifted)
                tempPage = tempPage.copy(items = remainingItems)
            } else {
                return page
            }
        }
        return tempPage
    }

    private fun rectsOverlap(
        x1: Int,
        y1: Int,
        w1: Int,
        h1: Int,
        x2: Int,
        y2: Int,
        w2: Int,
        h2: Int,
    ): Boolean {
        return x1 < x2 + w2 && x1 + w1 > x2 && y1 < y2 + h2 && y1 + h1 > y2
    }
}
