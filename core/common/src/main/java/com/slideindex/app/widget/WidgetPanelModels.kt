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

/**
 * 二级菜单**单项**内部的分隔符：`动作负载 \u0005 自定义名称 \u0005 自定义图标 key`。
 *
 * 动作负载只会用到 \u0002 / \u001D，不会撞上 \u0005，所以不需要转义。
 */
const val SUB_ACTION_FIELD_SEP = "\u0005"

/** 二级菜单**布局配置**内部的分隔符。 */
const val SUB_MENU_CONFIG_SEP = "\u0006"

/** 单个格子的二级菜单最多几项：再多弹出来就盖满整页了。 */
const val MAX_SUB_ACTIONS = 8

/**
 * 快捷方式 payload 的"落盘转义"。
 *
 * ⚠️ 为什么需要它：快捷方式 payload 自己就是分隔符拼出来的串（
 * `GestureShortcutPayload` 用 \u001C/\u001D/\u001F），而面板 item 编码的分隔符恰好也是
 * \u001D（item 间）/\u001E（字段间）/\u001F（页间）。payload 原样塞进 `intentUri`
 * 会把整个面板的编码**劈开**：带标签的 payload 在 \u001D 处断、多 intent 的在 \u001F 处
 * 把一页劈成两页。真机表现就是"Shizuku 扫出来的快捷方式有的点了没反应"
 * （能开的那批恰好是不含这些分隔符的 payload）。
 *
 * 处理：落盘前发现 payload 含面板分隔符 → base64 包一层（`b64:` 前缀）；
 * 读的时候见到前缀再解回来。旧数据没有前缀 = 原样返回（不迁移、不破坏）。
 */
object WidgetPanelFieldCodec {
    private const val B64_PREFIX = "b64:"

    fun encode(raw: String): String {
        if (raw.isEmpty()) return raw
        val needsEscape = raw.any { it == '\u001C' || it == '\u001D' || it == '\u001E' || it == '\u001F' }
        if (!needsEscape) return raw
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(B64_PREFIX.length + bytes.size * 2)
        sb.append(B64_PREFIX)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789ABCDEF"[v ushr 4])
            sb.append("0123456789ABCDEF"[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(stored: String): String {
        if (!stored.startsWith(B64_PREFIX)) return stored
        val hex = stored.substring(B64_PREFIX.length)
        if (hex.length % 2 != 0) return stored
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return stored
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return stored
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }
}

/**
 * 「长按二级菜单」的布局配置（每个格子各有一套）。
 *
 * - [columns] / [rows]：行列数（行数在项目数少于 行×列 时决定整块的垂直居中范围）；
 * - [offsetXDp] / [offsetYDp]：整块相对格子的偏移；
 * - [showLabels]：是否在图标下方画名称。
 */
data class WidgetPanelSubMenuConfig(
    val columns: Int = 3,
    val rows: Int = 2,
    val offsetXDp: Int = 0,
    val offsetYDp: Int = 0,
    val showLabels: Boolean = true,
    /** 相邻容器之间的净间距（dp），口径与轮盘的「容器间距」一致。 */
    val itemGapDp: Int = 8,
    /** 容器尺寸（dp，正方形边长）；圆形时即直径。 */
    val containerSizeDp: Int = 44,
    /** 容器圆角（dp）：等于容器尺寸一半时是圆形。 */
    val containerCornerDp: Int = 22,
    /** 容器背景色（ARGB）。 */
    val containerColorArgb: Long = DEFAULT_CONTAINER_COLOR_ARGB,
    /**
     * 名称文字颜色（ARGB）。
     *
     * ⚠️ 名称画在容器**外面**（在菜单底层的暗色遮罩上），所以它**不跟着容器颜色反色**，
     * 默认就是白色；只有用户显式改过才变。
     */
    val labelColorArgb: Long = DEFAULT_LABEL_COLOR_ARGB,
    /** 图标颜色（ARGB）；0 = 按容器背景自动反色（白底深色图标、深底白色图标）。 */
    val iconColorArgb: Long = DEFAULT_ICON_COLOR_ARGB,
    /** 图标占容器尺寸的百分比。 */
    val iconSizePercent: Int = 55,
    /** 名称文字大小百分比（100 = 基准 11sp）。 */
    val labelSizePercent: Int = 100,
    /** 容器背景不透明度（0 = 全透明，100 = 不透明）。 */
    val backgroundAlphaPercent: Int = 100,
) {
    fun normalized(): WidgetPanelSubMenuConfig = copy(
        columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
        rows = rows.coerceIn(MIN_ROWS, MAX_ROWS),
        offsetXDp = offsetXDp.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
        offsetYDp = offsetYDp.coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP),
        itemGapDp = itemGapDp.coerceIn(MIN_ITEM_GAP_DP, MAX_ITEM_GAP_DP),
        containerSizeDp = containerSizeDp.coerceIn(MIN_CONTAINER_SIZE_DP, MAX_CONTAINER_SIZE_DP),
        containerCornerDp = containerCornerDp.coerceIn(0, containerSizeDp.coerceIn(MIN_CONTAINER_SIZE_DP, MAX_CONTAINER_SIZE_DP) / 2),
        iconSizePercent = iconSizePercent.coerceIn(MIN_ICON_PERCENT, MAX_ICON_PERCENT),
        labelSizePercent = labelSizePercent.coerceIn(MIN_LABEL_PERCENT, MAX_LABEL_PERCENT),
        backgroundAlphaPercent = backgroundAlphaPercent.coerceIn(0, 100),
    )

    companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 6
        const val MIN_ROWS = 1
        const val MAX_ROWS = 6
        const val MIN_OFFSET_DP = -240
        const val MAX_OFFSET_DP = 240
        const val MIN_ITEM_GAP_DP = 0
        const val MAX_ITEM_GAP_DP = 24
        const val MIN_CONTAINER_SIZE_DP = 24
        const val MAX_CONTAINER_SIZE_DP = 72
        const val MIN_ICON_PERCENT = 20
        const val MAX_ICON_PERCENT = 90
        const val MIN_LABEL_PERCENT = 60
        const val MAX_LABEL_PERCENT = 160

        /** 默认容器底色：纯白（与按钮观感一致）。 */
        const val DEFAULT_CONTAINER_COLOR_ARGB = 0xFFFFFFFFL

        /** 默认文字颜色：白色（菜单底下是半透明黑遮罩）。 */
        const val DEFAULT_LABEL_COLOR_ARGB = 0xFFFFFFFFL

        /** 默认图标颜色：0 = 自动按容器背景反色。 */
        const val DEFAULT_ICON_COLOR_ARGB = 0L

        fun decode(raw: String?): WidgetPanelSubMenuConfig {
            if (raw.isNullOrBlank()) return WidgetPanelSubMenuConfig()
            val parts = raw.split(SUB_MENU_CONFIG_SEP)
            if (parts.size < 5) return WidgetPanelSubMenuConfig()
            val base = WidgetPanelSubMenuConfig()
            return WidgetPanelSubMenuConfig(
                columns = parts[0].toIntOrNull() ?: base.columns,
                rows = parts[1].toIntOrNull() ?: base.rows,
                offsetXDp = parts[2].toIntOrNull() ?: base.offsetXDp,
                offsetYDp = parts[3].toIntOrNull() ?: base.offsetYDp,
                showLabels = parts[4] != "0",
                // 下面几段都是后加的：旧记录没有 → 用默认值（向后兼容）。
                itemGapDp = parts.getOrNull(5)?.toIntOrNull() ?: base.itemGapDp,
                iconSizePercent = parts.getOrNull(6)?.toIntOrNull() ?: base.iconSizePercent,
                labelSizePercent = parts.getOrNull(7)?.toIntOrNull() ?: base.labelSizePercent,
                backgroundAlphaPercent = parts.getOrNull(8)?.toIntOrNull() ?: base.backgroundAlphaPercent,
                containerSizeDp = parts.getOrNull(9)?.toIntOrNull() ?: base.containerSizeDp,
                containerCornerDp = parts.getOrNull(10)?.toIntOrNull() ?: base.containerCornerDp,
                containerColorArgb = parts.getOrNull(11)?.toLongOrNull() ?: base.containerColorArgb,
                labelColorArgb = parts.getOrNull(12)?.toLongOrNull() ?: base.labelColorArgb,
                iconColorArgb = parts.getOrNull(13)?.toLongOrNull() ?: base.iconColorArgb,
            ).normalized()
        }
    }

    fun encode(): String = listOf(
        columns,
        rows,
        offsetXDp,
        offsetYDp,
        if (showLabels) "1" else "0",
        itemGapDp,
        iconSizePercent,
        labelSizePercent,
        backgroundAlphaPercent,
        containerSizeDp,
        containerCornerDp,
        containerColorArgb,
        labelColorArgb,
        iconColorArgb,
    ).joinToString(SUB_MENU_CONFIG_SEP)
}

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
     * 每项编码为 `动作负载\u0005自定义名称\u0005自定义图标key`（负载 =
     * `QuickLauncherItemCodec.encodeActionPayload`），多项用 [SUB_ACTION_SEP] 连接。
     * 旧数据没有这一列 → 空表（= 不弹菜单）；更旧的逐项格式是 `名称\u0003负载`，
     * 解析端两种都认。
     */
    val subActions: List<String> = emptyList(),
    /** 二级菜单布局（行列 / 偏移 / 是否显示名称）。旧数据解析成默认值。 */
    val subMenu: WidgetPanelSubMenuConfig = WidgetPanelSubMenuConfig(),
    /** 该格子的自定义图标 key（空 = 用目标自己的图标）。 */
    val iconKey: String = "",
    /** 格子容器底色（ARGB）；0 = 不画底色（保持透明）。 */
    val containerColorArgb: Long = 0L,
    /** 图标颜色（ARGB）；0 = 用目标自己的图标原色。 */
    val iconColorArgb: Long = 0L,
    /** 名称文字颜色（ARGB）；0 = 默认白色。 */
    val labelColorArgb: Long = 0L,
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
            item.subMenu.encode(),
            item.iconKey,
            item.containerColorArgb.toString(),
            item.iconColorArgb.toString(),
            item.labelColorArgb.toString(),
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
            subMenu = WidgetPanelSubMenuConfig.decode(parts.getOrNull(13)),
            iconKey = parts.getOrNull(14).orEmpty(),
            containerColorArgb = parts.getOrNull(15)?.toLongOrNull() ?: 0L,
            iconColorArgb = parts.getOrNull(16)?.toLongOrNull() ?: 0L,
            labelColorArgb = parts.getOrNull(17)?.toLongOrNull() ?: 0L,
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
