package com.slideindex.app.widget

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureShortcutPayload
import com.slideindex.app.launcher.QuickLauncherItemCodec

/**
 * 把面板里存下来的「快捷方式 payload」还原成可执行的 [GestureAction.LaunchShortcut]。
 *
 * ⚠️ 面板存的是**快速启动器条目**的 payload（三种形态），而 `GestureShortcutPayload`
 * 只认自己那种编码 —— 日志实测 `pkg=com.niven.translator id=fullscreen payloadKind=UNDECODABLE`
 * 就是这个错位：payload 本身是好的，只是按错的解码器去解。
 *
 * 与快捷轮盘添加快捷方式时的 `shortcutToLaunchShortcut` 同一套规则：
 * 1. `包名\u001C快捷id`（快速启动器的动态快捷方式）→ [GestureAction.LaunchShortcut.dynamic]
 * 2. `i:…` / `is:…`（intent / 多 intent）→ intent 形态
 * 3. `c:包名/类名`（组件）→ component 形态
 * 4. 已经是 GestureShortcutPayload 编码 → 直接用
 */
object WidgetPanelShortcutAction {

    fun fromItem(item: WidgetPanelItem): GestureAction.LaunchShortcut? {
        val label = item.label
        // 优先：面板自己存的原生 payload（老数据没转义 → 原样返回）。
        val raw = WidgetPanelFieldCodec.decode(item.intentUri).ifBlank { item.shortcutId }

        // 1) 直接就是 LaunchShortcut 的编码（c:/i:/is:/动态包名\u001Cid）。
        GestureShortcutPayload.decode(raw)?.let { return actionOf(raw, label) }

        // 2) 快速启动器的动态快捷方式编码：包名\u001C快捷id[\u001D标签]。
        QuickLauncherItemCodec.parseShortcutPayload(raw)?.let { (packageName, shortcutId) ->
            return GestureAction.LaunchShortcut.dynamic(packageName, shortcutId, label)
        }

        // 3) intent / 多 intent 编码。
        QuickLauncherItemCodec.parseIntentPayload(raw)?.let { uri ->
            return GestureAction.LaunchShortcut.intent(uri, label)
        }
        QuickLauncherItemCodec.parseIntentListPayload(raw)?.let { uris ->
            return intentsAction(uris, label)
        }

        // 4) 原始 android intent URI（老面板数据 / 手动创建）。
        if (raw.startsWith("intent:") || raw.startsWith("#")) {
            return GestureAction.LaunchShortcut.intent(raw, label)
        }

        // 5) 组件形态裸值：包名/类名。
        if (raw.count { it == '/' } == 1 && raw.contains('.')) {
            return GestureAction.LaunchShortcut.component(raw, label)
        }

        // 6) 兜底：拿 packageName + shortcutId 拼动态快捷方式（面板字段最原始的形态）。
        if (item.packageName.isNotBlank() && item.shortcutId.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, item.shortcutId, label)
        }
        return null
    }

    private fun actionOf(payloadKey: String, label: String): GestureAction.LaunchShortcut =
        GestureAction.LaunchShortcut(payloadKey = payloadKey, label = label)

    private fun intentsAction(
        uris: List<String>,
        label: String,
    ): GestureAction.LaunchShortcut {
        // 多 intent：逐个串成 GestureShortcutPayload 的多 intent 编码（is:…\u001F…）。
        val payload = GestureShortcutPayload.encodeIntents(uris, label)
        return GestureAction.LaunchShortcut(payloadKey = payload, label = label)
    }
}
