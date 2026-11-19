package com.slideindex.app.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureShortcutPayload
import com.slideindex.app.launcher.QuickLauncherItemCodec
import com.slideindex.app.util.AppShortcutLoader

/**
 * 把面板里存下来的「快捷方式」还原成可执行的 [GestureAction.LaunchShortcut]。
 *
 * 真机数据（DataStore dump）常见两种：
 * ```
 * A) 完整的快速启动器 payload：i:… / is:… / c:… / 包名\u001C快捷id
 * B) 残缺的 intent 片段：
 *    intentUri = flg=0x1000c000#Intent;launchFlags=…;component=pkg/TileActivity;end
 *    shortcutId = fullscreen（真实 id）
 * ```
 *
 * ⚠️ 关键：**必须优先按快捷方式 id 还原成它真正的 intent**。
 * 只按 component 启动会丢掉快捷方式自带的 extras —— 例如「开始录音」快捷方式
 * 在轮盘里能直接开始录音，按 component 启动却只是打开界面（真机反馈）。
 * 所以这里先异步问一次 [AppShortcutLoader]（与轮盘添加时的解析完全同源），
 * 拿到真实 intent 就按 intent/component/dynamic 的规范形态构造；
 * 解析不出来才退回「从片段里抠 component」。
 *
 * 快捷轮盘的添加流程用的是同一套映射（`shortcutToLaunchShortcut`），
 * 这里直接复用，保证两条链路行为一致。
 */
object WidgetPanelShortcutAction {

    private val CONTROL_CHARS = charArrayOf('\u001C', '\u001D', '\u001E', '\u001F')
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 解析快捷方式为可执行动作（可能要走 binder 查快捷方式信息，故放后台）。
     *
     * @param onReady 主线程回调；解析不出来时回退到 [fromItem] 的结果（可能为 null）。
     */
    fun resolveAsync(
        context: Context,
        item: WidgetPanelItem,
        onReady: (GestureAction.LaunchShortcut?) -> Unit,
    ) {
        val label = item.label
        val raw = WidgetPanelFieldCodec.decode(item.intentUri)
        val shortcutId = item.shortcutId.trim()

        // 快路径：payload 本身已经是完整编码 —— 不用查 binder。
        if (raw.startsWith("intent:") || raw.startsWith("#Intent;") ||
            raw.startsWith("c:") || raw.startsWith("i:") || raw.startsWith("is:") ||
            QuickLauncherItemCodec.parseShortcutPayload(raw) != null
        ) {
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
                    // 与轮盘/动作选择器完全同一套映射：service → dynamic，
                    // 有 intentUris → intent(s)，有组件 → component，否则 dynamic。
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

    fun fromItem(item: WidgetPanelItem): GestureAction.LaunchShortcut? {
        val label = item.label
        val raw = WidgetPanelFieldCodec.decode(item.intentUri)

        // 1) 干净的真实快捷方式 id → 动态快捷方式（执行器会解析出它真正的 intent）。
        val shortcutId = item.shortcutId.trim()
        val idLooksReal = shortcutId.isNotEmpty() &&
            shortcutId.none { it in CONTROL_CHARS } &&
            !shortcutId.startsWith("c:") &&
            !shortcutId.startsWith("i:") &&
            !shortcutId.startsWith("is:")
        if (idLooksReal && item.packageName.isNotBlank()) {
            return GestureAction.LaunchShortcut.dynamic(item.packageName, shortcutId, label)
        }

        // 2) 完整 intent URI / 快速启动器的 intent 编码。
        if (raw.startsWith("intent:") || raw.startsWith("#Intent;")) {
            return GestureAction.LaunchShortcut.intent(raw, label)
        }
        QuickLauncherItemCodec.parseIntentPayload(raw)?.let { uri ->
            return GestureAction.LaunchShortcut.intent(uri, label)
        }
        QuickLauncherItemCodec.parseIntentListPayload(raw)?.let { uris ->
            return intentsAction(uris, label)
        }

        // 3) 快速启动器的动态快捷方式编码：包名\u001C快捷id[\u001D标签]。
        QuickLauncherItemCodec.parseShortcutPayload(raw)?.let { pair ->
            return GestureAction.LaunchShortcut.dynamic(pair.first, pair.second, label)
        }

        // 4) 残缺片段里抠 component（最后手段：能打开界面，但可能丢 extras）。
        componentFrom(raw)?.let { flat ->
            return GestureAction.LaunchShortcut.component(flat, label)
        }
        if (raw.startsWith("c:")) {
            return GestureAction.LaunchShortcut(payloadKey = raw, label = label)
        }

        // 5) 已经是 LaunchShortcut 自己的编码。
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
