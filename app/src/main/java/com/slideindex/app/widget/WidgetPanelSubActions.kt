package com.slideindex.app.widget

import android.content.Context
import android.graphics.Bitmap
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.overlay.corner.CornerSlotIconBitmap
import com.slideindex.app.ui.gesturepicker.gestureActionLabelText

/**
 * 格子「长按二级菜单」的一条动作：配置时存的是 `标签\u0003动作负载`，
 * 运行时在这里解码成可执行的 [GestureAction]。
 *
 * 名称与图标都在**运行时**解析：
 * - 名称走 [gestureActionLabelText]（与动作选择器同一套本地化文案 + 模式后缀）；
 * - 图标走 [CornerSlotIconBitmap]（边角轮盘 / 快捷轮盘用的同一个解析器），
 *   应用图标、快捷方式图标、宏目标图标、全局图标覆盖都能正确出图。
 */
data class WidgetPanelSubAction(
    val label: String,
    val action: GestureAction,
)

object WidgetPanelSubActions {

    /** 把格子数据里的编码串解析成可执行菜单项（畸形项丢弃，不影响其余项）。 */
    fun parse(raw: List<String>, context: Context? = null): List<WidgetPanelSubAction> =
        raw.asSequence()
            .take(MAX_SUB_ACTIONS)
            .mapNotNull { entry ->
                val sep = entry.indexOf(SUB_ACTION_LABEL_SEP)
                val label: String
                val payload: String
                if (sep >= 0) {
                    label = entry.substring(0, sep)
                    payload = entry.substring(sep + SUB_ACTION_LABEL_SEP.length)
                } else {
                    label = ""
                    payload = entry
                }
                val action = QuickLauncherItemCodec.parseActionPayload(payload)
                    ?: return@mapNotNull null
                val name = context?.let { runCatching { gestureActionLabelText(it, action) }.getOrNull() }
                    .orEmpty()
                    .ifBlank { label }
                    .ifBlank { fallbackLabel(action) }
                WidgetPanelSubAction(name, action)
            }
            .toList()

    fun encode(label: String, action: GestureAction): String =
        label.ifBlank { fallbackLabel(action) } + SUB_ACTION_LABEL_SEP +
            QuickLauncherItemCodec.encodeActionPayload(action)

    /** 保存时的名称：优先用界面上的本地化文案。 */
    fun displayName(context: Context, action: GestureAction): String =
        runCatching { gestureActionLabelText(context, action) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackLabel(action)

    /** 没存标签时的兜底名（配置时一般都会有正规文案）。 */
    fun fallbackLabel(action: GestureAction): String = when (action) {
        is GestureAction.LaunchApp -> action.packageName.substringAfterLast('.')
        is GestureAction.LaunchShortcut -> action.payloadKey.substringAfterLast('/')
        is GestureAction.OpenLink -> action.url.take(20)
        is GestureAction.ExecuteShellCommand -> action.command.take(12)
        else -> action.type.name.lowercase().replace('_', ' ')
    }

    /**
     * 二级菜单项的图标。
     *
     * 直接复用边角轮盘的解析器：应用图标 / 快捷方式图标 / 宏目标 / 图标库覆盖都能出，
     * 不再走 `actionIconBitmapFull(withPlate = false)` —— 那条路对「启动应用 / 快捷方式」
     * 只会给通用矢量图标（真机反馈「应用程序和快捷方式的图标显示异常」）。
     */
    fun icon(context: Context, action: GestureAction, sizePx: Int): Bitmap? = runCatching {
        CornerSlotIconBitmap.get(
            context = context,
            action = action,
            sizePx = sizePx,
            // 菜单底是白圆，矢量图标用深色描线；位图图标不受 tint 影响。
            tintArgb = 0xFF333333.toInt(),
            plateFallback = false,
        )
    }.getOrNull()
}
