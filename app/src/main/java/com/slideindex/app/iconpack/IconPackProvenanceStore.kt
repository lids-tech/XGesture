package com.slideindex.app.iconpack

import android.content.Context

/**
 * 「这个图标来自哪个图标包」的来源记录。
 *
 * 图标本身是落盘 PNG（见 `ActionIconStorage.saveBitmapForGlobalKey`），
 * 所以图标包被卸载也不会掉图；这里只额外记一条来源，用于：
 * - 图标库那一行显示「来自 XX 图标包」
 * - 以后想做「按图标包重新应用」时知道该去哪找
 *
 * 用 SharedPreferences 而不是跟着 PNG 走，因为删除图标时两边要一起清。
 */
object IconPackProvenanceStore {

    private const val PREFS = "icon_pack_provenance"
    private const val KEY_PREFIX = "src:"
    private const val SEPARATOR = '\u0001'

    data class Source(
        val packPackage: String,
        val packLabel: String,
        val drawable: String,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        storageKey: String,
        packPackage: String,
        packLabel: String,
        drawable: String,
    ) {
        prefs(context).edit()
            .putString(
                KEY_PREFIX + storageKey,
                packPackage + SEPARATOR + packLabel + SEPARATOR + drawable,
            )
            .apply()
    }

    fun get(context: Context, storageKey: String): Source? {
        val raw = prefs(context).getString(KEY_PREFIX + storageKey, null) ?: return null
        val parts = raw.split(SEPARATOR)
        if (parts.size != 3) return null
        val packPackage = parts[0]
        if (packPackage.isBlank()) return null
        return Source(
            packPackage = packPackage,
            packLabel = parts[1].ifBlank { packPackage },
            drawable = parts[2],
        )
    }

    fun clear(context: Context, storageKey: String) {
        prefs(context).edit().remove(KEY_PREFIX + storageKey).apply()
    }
}
