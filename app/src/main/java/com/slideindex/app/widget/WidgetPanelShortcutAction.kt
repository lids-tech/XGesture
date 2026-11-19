package com.slideindex.app.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.slideindex.app.activity.ActivityShortcut
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureShortcutPayload
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.util.AppShortcutLoader

/**
 * 把面板里存下来的「快捷方式」还原成可执行的 [GestureAction.LaunchShortcut]。
 *
 * ⚠️ 关键：**优先用「我的直达」目录里的那条记录**（[ActivityShortcut]）。
 * 那是 Shizuku 扫描时抓到的**完整 intent（含 extras）**；
 * 而面板格子本身只存了 id / 一段可能残缺的 intent 片段 —— 只按组件启动会丢 extras，
 * 典型表现：「开始录音」只打开界面、不真录音（真机反馈）。
 *
 * 解析顺序（最可信 → 兜底）：
 * 1. 我的直达目录里按 (包名 + 快捷方式id / intent 含 id / 名称) 匹配 → 用它的 intent / 组件 / id
 * 2. 格子自带的完整 payload（i: / is: / c: / 包名\u001Cid / intent:）
 * 3. 后台问 AppShortcutLoader（与轮盘添加同源）解析真实 ShortcutInfo
 * 4. 从残缺片段里抠 component（最后手段，可能丢 extras）
 */
object WidgetPanelShortcutAction {

    private val CONTROL_CHARS = charArrayOf('\u001C', '\u001D', '\u001E', '\u001F')
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @param catalog 「我的直达」目录（`settings.activityShortcuts`），与我的直达启动同一数据源
     */
    fun resolveAsync(
        context: Context,
        item: WidgetPanelItem,
        catalog: List<ActivityShortcut> = emptyList(),
        onReady: (GestureAction.LaunchShortcut?) -> Unit,
    ) {
        val label = item.label
        val raw = WidgetPanelFieldCodec.decode(item.intentUri)
        val shortcutId = item.shortcutId.trim()

        // 1) 我的直达目录命中 → 直接用它的完整 intent（extras 不丢）。
        catalogAction(catalog, item.packageName, shortcutId, label)?.let {
            onReady(it)
            return
        }

        // 2) 快路径：payload 本身已是完整编码，不用查 binder。
        if (isFullPayload(raw)) {
            onReady(fromItem(item))
            return
        }

        val idLooksReal = shortcutId.isNotEmpty() &&
            shortcutId.none { it in CONTROL_CHARS } &&
            !shortcutId.startsWith("c:") &&
            !shortcutId.startsWith("i:") &&
            !shortcutId.startsWith("is:")
        if (!idLooksReal || item.packageName.isBlank()) {
            onReady(fromItem(item))
            return
        }

        // 3) 后台解析真实 ShortcutInfo。
        val appContext = context.applicationContext
        val packageName = item.packageName
        Thread {
            val resolved = runCatching {
                AppShortcutLoader.resolveShortcutForLaunch(
                    context = appContext,
                    packageName = packageName,
                    shortcutId = shortcutId,
                    label = label,
                )
            }.getOrNull()
            val action = if (resolved != null) {
                runCatching {
                    com.slideindex.app.ui.gesturepicker.shortcutToLaunchShortcut(
                        resolved,
                        packageName,
                    )
                }.getOrNull() ?: fromItem(item)
            } else {
                fromItem(item)
            }
            mainHandler.post { onReady(action) }
        }.start()
    }

    /** 与「我的直达」启动完全同源：目录里那条记录决定怎么开。 */
    private fun catalogAction(
        catalog: List<ActivityShortcut>,
        packageName: String,
        shortcutId: String,
        label: String,
    ): GestureAction.LaunchShortcut? {
        if (catalog.isEmpty()) return null
        val candidates = catalog.filter { it.packageName == packageName }
        if (candidates.isEmpty()) return null
        val hit = candidates.firstOrNull { sc ->
            (shortcutId.isNotBlank() && sc.shortcutId == shortcutId) ||
                (shortcutId.isNotBlank() && sc.intentUris.any { it.contains(shortcutId) })
        } ?: candidates.firstOrNull { it.label == label }
        ?: return null
        return when (hit.kind) {
            com.slideindex.app.activity.ActivityShortcutKind.INTENT -> when {
                hit.intentUris.size == 1 ->
                    GestureAction.LaunchShortcut.intent(hit.intentUris[0], hit.label)
                hit.intentUris.size > 1 ->
                    GestureAction.LaunchShortcut.intents(hit.intentUris, hit.label)
                else -> null
            }
            com.slideindex.app.activity.ActivityShortcutKind.COMPONENT ->
                hit.activityClassName.takeIf { it.isNotBlank() }?.let { cls ->
                    GestureAction.LaunchShortcut.component("$packageName/$cls", hit.label)
                }
            com.slideindex.app.activity.ActivityShortcutKind.DYNAMIC ->
                hit.shortcutId.takeIf { it.isNotBlank() }?.let { id ->
                    GestureAction.LaunchShortcut.dynamic(packageName, id, hit.label)
                }
        }
    }

    private fun isFullPayload(raw: String): Boolean =
        raw.startsWith("intent:") || raw.startsWith("#Intent;") ||
            raw.startsWith("c:") || raw.startsWith("i:") || raw.startsWith("is:") ||
            QuickLauncherItemCodec.parseShortcutPayload(raw) != null

    fun fromItem(item: WidgetPanelItem): GestureAction.LaunchShortcut? {
        val label = item.label
        val raw = WidgetPanelFieldCodec.decode(item.intentUri)

        val shortcutId = item.shortcutId.trim()
        val idLooksReal = shortcutId.isNotEmpty() &&
            shortcutId.none { it in CONTROL_CHARS } &&
            !shortcutId.startsWith("c:") &&
            !shortcutId.startsWith("i:") &&
            !shortcutId.startsWith("is:")
        if (idLooksReal && item.packageName.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, shortcutId, label)
        }

        if (raw.startsWith("intent:") || raw.startsWith("#Intent;")) {
            return GestureAction.LaunchShortcut.intent(raw, label)
        }
        QuickLauncherItemCodec.parseIntentPayload(raw)?.let { uri ->
            return GestureAction.LaunchShortcut.intent(uri, label)
        }
        QuickLauncherItemCodec.parseIntentListPayload(raw)?.let { uris ->
            return intentsAction(uris, label)
        }
        QuickLauncherItemCodec.parseShortcutPayload(raw)?.let { pair ->
            return GestureAction.LaunchShortcut.dynamic(pair.first, pair.second, label)
        }
        componentFrom(raw)?.let { flat ->
            return GestureAction.LaunchShortcut.component(flat, label)
        }
        if (raw.startsWith("c:")) {
            return GestureAction.LaunchShortcut(payloadKey = raw, label = label)
        }
        if (GestureShortcutPayload.decode(raw) != null) {
            return GestureAction.LaunchShortcut(payloadKey = raw, label = label)
        }
        if (item.packageName.isNotBlank() && shortcutId.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, shortcutId, label)
        }
        return null
    }

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
