package com.slideindex.app.widget

import android.content.Context
import android.graphics.Bitmap
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.overlay.corner.CornerSlotIconBitmap
import com.slideindex.app.ui.ActionIconBitmap
import com.slideindex.app.ui.gesturepicker.gestureActionLabelText

/**
 * 格子「长按二级菜单」的一条动作。
 *
 * 三个字段都可配置：动作本身、显示名称、图标 key（`ActionIconKeyCodec` 那套编码，
 * 与「给动作设置图标」完全同源）。
 */
data class WidgetPanelSubAction(
    val action: GestureAction,
    /** 用户自定义名称；空 = 用动作自身的本地化文案。 */
    val customName: String = "",
    /** 自定义图标 key；空 = 用该动作自己的图标（应用图标 / 快捷方式图标 / 类型图标）。 */
    val iconKey: String = "",
    /** 该格单独的容器颜色（ARGB）；0 = 跟随菜单的统一设置。 */
    val containerColorArgb: Long = 0L,
    /** 该格单独的文字 / 图标颜色（ARGB）；0 = 跟随菜单的统一设置。 */
    val iconTintArgb: Long = 0L,
)

object WidgetPanelSubActions {

    /**
     * 解析格子数据里的编码串。
     *
     * 兼容两种历史格式：
     * - 现行：`负载\u0005名称\u0005图标key`
     * - 早期：`名称\u0003负载`（没有图标）
     */
    fun parse(raw: List<String>): List<WidgetPanelSubAction> =
        raw.asSequence()
            .take(MAX_SUB_ACTIONS)
            .mapNotNull { entry ->
                val fields = entry.split(SUB_ACTION_FIELD_SEP)
                val payload: String
                val name: String
                val iconKey: String
                val containerColorArgb: Long
                val iconTintArgb: Long
                if (fields.size >= 1 && entry.contains(SUB_ACTION_FIELD_SEP)) {
                    payload = fields.getOrElse(0) { "" }
                    name = fields.getOrElse(1) { "" }
                    iconKey = fields.getOrElse(2) { "" }
                    containerColorArgb = fields.getOrNull(3)?.toLongOrNull() ?: 0L
                    iconTintArgb = fields.getOrNull(4)?.toLongOrNull() ?: 0L
                } else {
                    // 早期格式：名称\u0003负载
                    val sep = entry.indexOf(SUB_ACTION_LABEL_SEP)
                    if (sep >= 0) {
                        name = entry.substring(0, sep)
                        payload = entry.substring(sep + SUB_ACTION_LABEL_SEP.length)
                    } else {
                        name = ""
                        payload = entry
                    }
                    iconKey = ""
                    containerColorArgb = 0L
                    iconTintArgb = 0L
                }
                val action = QuickLauncherItemCodec.parseActionPayload(payload)
                    ?: return@mapNotNull null
                WidgetPanelSubAction(
                    action = action,
                    customName = name,
                    iconKey = iconKey,
                    containerColorArgb = containerColorArgb,
                    iconTintArgb = iconTintArgb,
                )
            }
            .toList()

    /** 显示名称：自定义优先，否则用动作的本地化文案。 */
    fun displayName(context: Context, item: WidgetPanelSubAction): String =
        item.customName.ifBlank {
            runCatching { gestureActionLabelText(context, item.action) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackLabel(item.action)
        }

    fun encode(
        action: GestureAction,
        customName: String,
        iconKey: String,
        containerColorArgb: Long = 0L,
        iconTintArgb: Long = 0L,
    ): String = listOf(
        QuickLauncherItemCodec.encodeActionPayload(action),
        customName,
        iconKey,
        containerColorArgb.toString(),
        iconTintArgb.toString(),
    ).joinToString(SUB_ACTION_FIELD_SEP)

    /** 没存名称时的兜底名（配置时一般都会有正规文案）。 */
    fun fallbackLabel(action: GestureAction): String = when (action) {
        is GestureAction.LaunchApp -> action.packageName.substringAfterLast('.')
        is GestureAction.LaunchShortcut -> action.payloadKey.substringAfterLast('/')
        is GestureAction.OpenLink -> action.url.take(20)
        is GestureAction.ExecuteShellCommand -> action.command.take(12)
        else -> action.type.name.lowercase().replace('_', ' ')
    }

    /**
     * 菜单项图标：
     * - 有自定义图标 key → 与「给动作设置图标」同一渲染通道（字体图标 / 文字 / 图片）；
     * - 否则 → 边角轮盘的解析器（应用图标 / 快捷方式图标 / 宏目标 / 类型图标）。
     */
    fun icon(
        context: Context,
        item: WidgetPanelSubAction,
        sizePx: Int,
        tintArgb: Int = DEFAULT_ICON_TINT_ARGB,
    ): Bitmap? {
        if (item.iconKey.isNotBlank()) {
            runCatching {
                ActionIconBitmap.loadByKey(
                    context = context,
                    key = item.iconKey,
                    sizePx = sizePx,
                    tintArgb = tintArgb,
                    withPlate = false,
                    action = item.action,
                )
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            CornerSlotIconBitmap.get(
                context = context,
                action = item.action,
                sizePx = sizePx,
                tintArgb = tintArgb,
                plateFallback = false,
            )
        }.getOrNull()
    }

    /** 默认图标着色：深灰（白底容器上清晰）。 */
    const val DEFAULT_ICON_TINT_ARGB: Int = 0xFF333333.toInt()
}
