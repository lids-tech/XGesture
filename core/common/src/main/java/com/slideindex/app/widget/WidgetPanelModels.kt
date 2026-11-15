package com.slideindex.app.widget

const val ITEM_TYPE_WIDGET = "widget"
const val ITEM_TYPE_APP = "app"
const val ITEM_TYPE_SHORTCUT = "shortcut"
const val ITEM_TYPE_ACTION = "action"

/**
 * 格子「长按二级菜单」里，单项的**标签**与**动作负载**之间的分隔符。
 *
 * 动作负载本身（[com.slideindex.app.launcher.QuickLauncherItemCodec]）只用 \u0002 与 \u001D，
 * 不会撞上 \u0003 / \u0004，也不需要转义。
 */
const val SUB_ACTION_LABEL_SEP = "\u0003"

/** 格子「长按二级菜单」里，多项之间的分隔符。 */
const val SUB_ACTION_SEP = "\u0004"

/** 单个格子的二级菜单最多几项：再多弹出来就盖满整页了。 */
const val MAX_SUB_ACTIONS = 8

data class WidgetPanelItem(
    val appWidgetId: Int,
    val x: Int,
    val y: Int,
    val spanX: Int,
    val spanY: Int,
    val label: String = "",
    val itemType: String = ITEM_TYPE_WIDGET,
    val packageName: String = "",
    val className: String = "",
    val intentUri: String = "",
    val shortcutId: String = "",
    /** 应用分身所属 user（0 = 主实例）。旧数据没有这个字段，解析出来就是 0。 */
    val cloneUserId: Int = 0,
    /**
     * 长按该格子弹出的「二级菜单」动作，最多 [MAX_SUB_ACTIONS] 项。
     *
     * 每项编码为 `标签\u0003动作负载`（负载 = `QuickLauncherItemCodec.encodeActionPayload`），
     * 多项用 [SUB_ACTION_SEP] 连接；旧数据没有这一列，解析出来是空表（= 不弹菜单）。
     */
    val subActions: List<String> = emptyList(),
)

data class WidgetPanelPage(
    val id: Long = 1L,
    val name: String = "",
    val columnCount: Int = 4,
    val rowCount: Int = 26,
    val visibleRowCount: Int = 6,
    val cellWidthDp: Int = 62,
    /**
     * 距屏幕左边的边距（dp）。[LEGACY_CENTERED_MARGIN_LEFT_DP] 与 0 都表示「居中」。
     *
     * 历史原因：这个字段在布局里从未被读取过，老数据的默认值一律是 18；如果直接把 18 当成
     * 「距左 18dp」，所有存量面板会从居中跳到左边。因此 18 被保留为「未设置 = 居中」的哨兵值。
     */
    val marginLeftDp: Int = LEGACY_CENTERED_MARGIN_LEFT_DP,
    val marginTopDp: Int = 100,
    val items: List<WidgetPanelItem> = emptyList(),
    val overlayAlpha: Float = 0.25f,
    val blurEnabled: Boolean = true,
) {
    /** 真正生效的左边距：0 = 居中。 */
    val resolvedMarginLeftDp: Int
        get() = if (marginLeftDp == LEGACY_CENTERED_MARGIN_LEFT_DP) 0 else marginLeftDp.coerceIn(0, 500)

    companion object {
        /** 旧数据里 `marginLeftDp` 的隐式默认值，语义等同「居中」。 */
        const val LEGACY_CENTERED_MARGIN_LEFT_DP = 18
    }
}

object WidgetPanelDefaults {
    val defaultPage: WidgetPanelPage = WidgetPanelPage()

    fun effectivePages(pages: List<WidgetPanelPage>): List<WidgetPanelPage> =
        pages.ifEmpty { listOf(defaultPage) }
}

object WidgetPanelCodec {
    private const val PAGE_SEP = "\u001F"
    private const val FIELD_SEP = "\u001E"
    private const val ITEM_SEP = "\u001D"

    fun encodePage(page: WidgetPanelPage): String {
        val header = listOf(
            page.id.toString(),
            page.name,
            page.columnCount.toString(),
            page.rowCount.toString(),
            page.overlayAlpha.toString(),
            page.blurEnabled.toString(),
            page.visibleRowCount.toString(),
            page.cellWidthDp.toString(),
            page.marginLeftDp.toString(),
            page.marginTopDp.toString(),
        ).joinToString(FIELD_SEP)
        val items = page.items.joinToString(ITEM_SEP) { encodeItem(it) }
        return if (items.isEmpty()) header else "$header$FIELD_SEP$items"
    }

    fun decodePage(raw: String): WidgetPanelPage? {
        var cursor = 0
        fun nextHeaderField(): String? {
            if (cursor > raw.length) return null
            val sep = raw.indexOf(FIELD_SEP, cursor)
            return if (sep < 0) {
                raw.substring(cursor).also { cursor = raw.length }
            } else {
                raw.substring(cursor, sep).also { cursor = sep + 1 }
            }
        }

        val id = nextHeaderField()?.toLongOrNull() ?: return null
        val name = nextHeaderField() ?: return null
        val columns = nextHeaderField()?.toIntOrNull()?.coerceIn(2, 20) ?: return null
        val rows = nextHeaderField()?.toIntOrNull()?.coerceIn(3, 40) ?: return null
        val alpha = nextHeaderField()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.55f
        val blur = nextHeaderField()?.toBooleanStrictOrNull() ?: true
        val visibleRows = nextHeaderField()?.toIntOrNull()?.coerceIn(1, 40) ?: 6
        val cellWidth = nextHeaderField()?.toIntOrNull()?.coerceIn(20, 200) ?: 62
        val marginLeft = nextHeaderField()?.toIntOrNull()?.coerceIn(0, 500) ?: 18
        val marginTop = nextHeaderField()?.toIntOrNull()?.coerceIn(0, 500) ?: 100
        val itemsRaw = if (cursor < raw.length) raw.substring(cursor) else ""
        val items = if (itemsRaw.isNotBlank()) {
            itemsRaw.split(ITEM_SEP).mapNotNull { decodeItem(it) }
        } else {
            emptyList()
        }
        return WidgetPanelPage(id, name, columns, rows, visibleRows, cellWidth, marginLeft, marginTop, items, alpha, blur)
    }

    private fun encodeItem(item: WidgetPanelItem): String =
        listOf(
            item.appWidgetId.toString(),
            item.x.toString(),
            item.y.toString(),
            item.spanX.toString(),
            item.spanY.toString(),
            item.label,
            item.itemType,
            item.packageName,
            item.className,
            item.intentUri,
            item.shortcutId,
            item.cloneUserId.toString(),
            item.subActions.joinToString(SUB_ACTION_SEP),
        ).joinToString(FIELD_SEP)

    private fun decodeItem(raw: String): WidgetPanelItem? {
        val parts = raw.split(FIELD_SEP)
        if (parts.size < 5) return null
        val id = parts[0].toIntOrNull() ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val spanX = parts[3].toIntOrNull()?.coerceAtLeast(1) ?: return null
        val spanY = parts[4].toIntOrNull()?.coerceAtLeast(1) ?: return null
        val label = parts.getOrNull(5).orEmpty()
        val itemType = parts.getOrNull(6).takeUnless { it.isNullOrEmpty() } ?: ITEM_TYPE_WIDGET
        val packageName = parts.getOrNull(7).orEmpty()
        val className = parts.getOrNull(8).orEmpty()
        val intentUri = parts.getOrNull(9).orEmpty()
        val shortcutId = parts.getOrNull(10).orEmpty()
        val cloneUserId = parts.getOrNull(11)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val subActions = parts.getOrNull(12)
            .orEmpty()
            .split(SUB_ACTION_SEP)
            .filter { it.isNotBlank() }
            .take(MAX_SUB_ACTIONS)
        return WidgetPanelItem(
            appWidgetId = id, x = x, y = y, spanX = spanX, spanY = spanY, label = label,
            itemType = itemType, packageName = packageName, className = className,
            intentUri = intentUri, shortcutId = shortcutId, cloneUserId = cloneUserId,
            subActions = subActions,
        )
    }

    fun encodeAll(pages: List<WidgetPanelPage>): Set<String> =
        if (pages.isEmpty()) emptySet() else setOf(pages.joinToString(PAGE_SEP) { encodePage(it) })

    fun decodeAll(raw: Set<String>): List<WidgetPanelPage> {
        if (raw.isEmpty()) return emptyList()
        val decoded = if (raw.size == 1) {
            val only = raw.first()
            if (PAGE_SEP in only) {
                only.split(PAGE_SEP).mapNotNull { decodePage(it) }
            } else {
                listOfNotNull(decodePage(only))
            }
        } else {
            raw.mapNotNull { decodePage(it) }
        }
        return decoded
    }
}
