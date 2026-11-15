package com.slideindex.app.widget

import android.content.Context
import android.graphics.Bitmap
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.overlay.panels.PanelRuntimeHooks

/**
 * 格子「长按二级菜单」的一条动作：配置时存的是 `标签\u0003动作负载`，
 * 运行时在这里解码成可执行的 [GestureAction]。
 */
data class WidgetPanelSubAction(
    val label: String,
    val action: GestureAction,
)

object WidgetPanelSubActions {

    /** 把格子数据里的编码串解析成可执行菜单项。畸形项直接丢弃，不影响其余项。 */
    fun parse(raw: List<String>): List<WidgetPanelSubAction> =
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
                WidgetPanelSubAction(label.ifBlank { fallbackLabel(action) }, action)
            }
            .toList()

    fun encode(label: String, action: GestureAction): String =
        label.ifBlank { fallbackLabel(action) } + SUB_ACTION_LABEL_SEP +
            QuickLauncherItemCodec.encodeActionPayload(action)

    /** 没存标签时的兜底名（配置时一般都会有正规文案）。 */
    fun fallbackLabel(action: GestureAction): String = when (action) {
        is GestureAction.LaunchApp -> action.packageName.substringAfterLast('.')
        is GestureAction.LaunchShortcut -> action.payloadKey.substringAfterLast('/')
        is GestureAction.OpenLink -> action.url.take(20)
        is GestureAction.ExecuteShellCommand -> action.command.take(12)
        else -> action.type.name.lowercase().replace('_', ' ')
    }

    /** 二级菜单项的图标（走全局动作图标通道，与应用 / 轮盘同一套外观）。 */
    fun icon(context: Context, action: GestureAction, sizePx: Int): Bitmap? = runCatching {
        PanelRuntimeHooks.actionIconBitmapFull?.invoke(
            context,
            action,
            sizePx,
            0xFF000000.toInt(),
            false,
            false,
            0,
        )
    }.getOrNull()
}
