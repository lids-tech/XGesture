package com.slideindex.app.iconpack

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 「全局图标包」的当前选择。
 *
 * 语义：
 * - [pack] 非空 = 主题层生效，所有**没被单独设过图标**的应用都套用这个包；
 * - 空 = 不套用（回退系统图标）；
 * - 单独设置（`GlobalIconStore.appIcon`）永远优先，不受这里影响。
 *
 * 只有一条存储，所以“关闭”就是清空，不另设开关——避免“开关关了但包还选着”的歧义。
 */
object IconThemeStore {

    private const val PREFS = "icon_theme"
    private const val KEY_PACK = "theme_pack"
    private const val KEY_PACK_LABEL = "theme_pack_label"

    /** 主题选择变更计数：渲染侧靠它失效缓存（约等于 `GlobalIconStore.revision` 的主题版）。 */
    var revision by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pack(context: Context): String? =
        prefs(context).getString(KEY_PACK, null)?.takeIf { it.isNotBlank() }

    /** 选中的图标包显示名（选包时一起存，避免每次去查 PackageManager）。 */
    fun label(context: Context): String? =
        prefs(context).getString(KEY_PACK_LABEL, null)?.takeIf { it.isNotBlank() }

    fun setPack(context: Context, packageName: String?, label: String? = null) {
        val editor = prefs(context).edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(KEY_PACK)
            editor.remove(KEY_PACK_LABEL)
        } else {
            editor.putString(KEY_PACK, packageName)
            editor.putString(KEY_PACK_LABEL, label?.takeIf { it.isNotBlank() } ?: packageName)
        }
        editor.apply()
        revision++
        // 主题变了：清缓存 + 通知所有面板重绘（与改单设图标走同一条通知链）
        com.slideindex.app.ui.AppIconOverride.clear()
        com.slideindex.app.ui.GlobalIconStore.notifyIconSourceChanged()
    }

    fun isEnabled(context: Context): Boolean = pack(context) != null
}
