package com.slideindex.app.widget

import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureShortcutPayload
import com.slideindex.app.launcher.QuickLauncherItemCodec

/**
 * 把面板里存下来的「快捷方式」还原成可执行的 [GestureAction.LaunchShortcut]。
 *
 * 真机数据（从 DataStore 里 dump 出来的）长这样：
 * ```
 * label       = Fullscreen Translate
 * packageName = com.niven.translator
 * shortcutId  = fullscreen
 * intentUri   = flg=0x1000c000#Intent;launchFlags=0x10000000;
 *               component=com.niven.translator/com.niven.translate.TileActivity;end
 * ```
 * 注意 `intentUri` 是**残缺的 intent URI 片段**（少了 `intent:#Intent;` 前缀），
 * `Intent.parseUri` 解不了；而 `shortcutId` 是真实的快捷方式 id。
 *
 * 所以按「最确定 → 最兜底」的顺序还原：
 * 1. 从片段里抠 `component=包名/类名` → 组件形态（执行器会走 ActivityShortcutLauncher，
 *    已导出普通启动、未导出走 Root/Shizuku —— TileActivity 这类基本都是未导出的）
 * 2. `shortcutId` 是干净的 id → 动态快捷方式（与快捷轮盘添加时同一形态）
 * 3. 完整 intent URI / 快速启动器的 intent 编码
 * 4. 快速启动器的动态快捷方式编码（包名\u001Cid）
 * 5. 已经是 LaunchShortcut 原生编码
 * 6. 兜底：面板字段拼动态快捷方式
 */
object WidgetPanelShortcutAction {

    /** 快捷方式 payload 里的控制字符（面板编码分隔符，不能出现在 id 里）。 */
    private val CONTROL_CHARS = charArrayOf('\u001C', '\u001D', '\u001E', '\u001F')

    fun fromItem(item: WidgetPanelItem): GestureAction.LaunchShortcut? {
        val label = item.label
        val raw = WidgetPanelFieldCodec.decode(item.intentUri)

        // 1) 从 intent URI（含残缺片段）里抠 component。
        componentFrom(raw)?.let { flat ->
            return GestureAction.LaunchShortcut.component(flat, label)
        }

        // 2) 干净的快捷方式 id → 动态快捷方式。
        val shortcutId = item.shortcutId.trim()
        val idLooksReal = shortcutId.isNotEmpty() &&
            shortcutId.none { it in CONTROL_CHARS } &&
            !shortcutId.startsWith("c:") &&
            !shortcutId.startsWith("i:") &&
            !shortcutId.startsWith("is:")
        if (idLooksReal && item.packageName.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, shortcutId, label)
        }

        // 3) 完整 intent URI / 快速启动器的 intent 编码。
        if (raw.startsWith("intent:") || raw.startsWith("#Intent;")) {
            return GestureAction.LaunchShortcut.intent(raw, label)
        }
        QuickLauncherItemCodec.parseIntentPayload(raw)?.let { uri ->
            return GestureAction.LaunchShortcut.intent(uri, label)
        }
        QuickLauncherItemCodec.parseIntentListPayload(raw)?.let { uris ->
            return intentsAction(uris, label)
        }

        // 4) 快速启动器的动态快捷方式编码：包名\u001C快捷id[\u001D标签]。
        QuickLauncherItemCodec.parseShortcutPayload(raw)?.let { pair ->
            return GestureAction.LaunchShortcut.dynamic(pair.first, pair.second, label)
        }

        // 5) 已经是 LaunchShortcut 自己的编码（c:/i:/is:）。
        if (GestureShortcutPayload.decode(raw) != null) {
            return GestureAction.LaunchShortcut(payloadKey = raw, label = label)
        }

        // 6) 兜底：面板字段。
        if (item.packageName.isNotBlank() && shortcutId.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, shortcutId, label)
        }
        return null
    }

    /**
     * 从 intent URI / 残缺片段里抠出 `component=包名/类名`。
     *
     * 例：`flg=0x1000c000#Intent;launchFlags=0x10000000;component=com.niven.translator/
     * com.niven.translate.TileActivity;end` → `com.niven.translator/com.niven.translate.TileActivity`
     */
    private fun componentFrom(raw: String): String? {
        if (raw.isBlank()) return null
        val match = Regex("component=([^;&\\s]+/[^;&\\s]+)").find(raw) ?: return null
        val flat = match.groupValues[1].trim()
        return flat.takeIf { it.count { c -> c == '/' } == 1 && it.contains('.') }
    }

    private fun intentsAction(
        uris: List<String>,
        label: String,
    ): GestureAction.LaunchShortcut {
        val payload = GestureShortcutPayload.encodeIntents(uris, label)
        return GestureAction.LaunchShortcut(payloadKey = payload, label = label)
    }
}
