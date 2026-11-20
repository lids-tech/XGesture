package com.slideindex.app.ui

import android.content.Context
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.slideindex.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 三个图标库（总共 10635 个图标），全部以**字体**形式随 APK 打包。
 *
 * | 库 | 数量 | 许可 | 风格 |
 * |---|---|---|---|
 * | Lucide | 2159 | ISC | 细线（Feather 血脉，最接近一墨用的 Iconoir） |
 * | Remix Icon | 3229 | Apache-2.0 | line + fill 双风格 |
 * | Tabler | 5247 | MIT | 技术感、数量最多 |
 *
 * 名字 → 码位的表放在 assets 的 tsv 文件里，运行时读一次并缓存；不联网。
 */
enum class IconFontLibrary(
    val id: String,
    val label: String,
    @FontRes val fontRes: Int,
    val assetFile: String,
    /** 存储键前缀（`text:` 用 `t:`，所以 Tabler 用 `tb:` 避免歧义）。 */
    val keyPrefix: String,
) {
    LUCIDE("lucide", "Lucide", R.font.lucide, "lucide_icons.tsv", "l:"),
    REMIX("remix", "Remix Icon", R.font.remixicon, "remix_icons.tsv", "r:"),
    TABLER("tabler", "Tabler", R.font.tabler_icons, "tabler_icons.tsv", "tb:"),
    ;

    val fontFamily: FontFamily by lazy { FontFamily(Font(fontRes)) }

    companion object {
        fun fromKey(key: String?): IconFontLibrary? {
            if (key == null) return null
            // 先长前缀（tb:）再短前缀（t: 是文字图标，不在这里）
            return entries.firstOrNull { !it.keyPrefix.equals("t:") && key.startsWith(it.keyPrefix) }
        }
    }
}

/** 图标库里的一个条目。 */
data class IconFontItem(
    val library: IconFontLibrary,
    val name: String,
    val codePoint: Int,
) {
    val key: String get() = library.keyPrefix + name
    val displayName: String
        get() = name.removeSuffix("-line").removeSuffix("-fill")
}

object IconFontCatalog {

    private val cache = ConcurrentHashMap<IconFontLibrary, List<IconFontItem>>()

    fun entries(context: Context, library: IconFontLibrary): List<IconFontItem> =
        cache.getOrPut(library) {
            runCatching {
                context.assets.open(library.assetFile).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val parts = line.split('\t')
                        if (parts.size != 2) return@mapNotNull null
                        val cp = parts[1].toIntOrNull() ?: return@mapNotNull null
                        IconFontItem(library, parts[0], cp)
                    }.toList()
                }
            }.getOrDefault(emptyList())
        }

    /** 某个存储键对应的（字体, 字符）；不是字体图标或找不到返回 null。 */
    fun resolve(context: Context, key: String?): Pair<FontFamily, String>? {
        if (key == null) return null
        val library = IconFontLibrary.fromKey(key) ?: return null
        val name = key.removePrefix(library.keyPrefix)
        val item = entries(context, library).firstOrNull { it.name == name } ?: return null
        return library.fontFamily to String(Character.toChars(item.codePoint))
    }

    /**
     * 跨库搜索。
     *
     * @param library null = 全部库
     * @param limit 返回上限（跨库搜索时避免一次渲染上万项）
     */
    fun search(
        context: Context,
        query: String,
        library: IconFontLibrary?,
        limit: Int = 400,
    ): List<IconFontItem> {
        val q = query.trim().lowercase()
        val libraries = library?.let { listOf(it) } ?: IconFontLibrary.entries
        val result = ArrayList<IconFontItem>(limit)
        for (lib in libraries) {
            val items = entries(context, lib)
            if (q.isEmpty()) {
                result.addAll(items.take(maxOf(1, limit / libraries.size)))
            } else {
                items.firstOrNull { it.name.contains(q) }?.let { }
                for (item in items) {
                    if (item.name.contains(q)) {
                        result.add(item)
                        if (result.size >= limit) return result
                    }
                }
            }
            if (result.size >= limit) break
        }
        return result
    }
}

/**
 * 图标存储键的编解码。
 *
 * - `t:文字` 文字图标
 * - `file:路径` 相册/应用图标（PNG）
 * - `l:名字` / `r:名字` / `tb:名字` 三个字体库
 */
object ActionIconKeyCodec {

    private const val TEXT = "t"
    private const val FILE = "file"

    /** 文字图标字号默认百分比。 */
    const val DEFAULT_TEXT_SCALE = 100
    const val MIN_TEXT_SCALE = 40
    const val MAX_TEXT_SCALE = 200

    fun text(value: String): String = "$TEXT:$value"

    /**
     * 文字图标（可带字号）。
     *
     * 编码：字号为默认 100% 时保持老格式 `t:文字`（兼容旧数据）；
     * 非默认时用 `t:字号:文字`。字号只在 40..200 之间才会被识别，
     * 所以老数据里以数字+冒号开头的文字（极罕见）不会被误判。
     */
    fun text(value: String, scalePercent: Int = DEFAULT_TEXT_SCALE): String {
        val scale = scalePercent.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        return if (scale == DEFAULT_TEXT_SCALE) "$TEXT:$value" else "$TEXT:$scale:$value"
    }

    fun file(path: String): String = "$FILE:$path"

    fun isFile(key: String?): Boolean = key?.startsWith("$FILE:") == true

    /** 取文字图标里的文字（自动去掉可选的字号前缀）。 */
    fun textOf(key: String?): String? {
        if (key == null || !key.startsWith("$TEXT:")) return null
        val body = key.removePrefix("$TEXT:")
        val parts = body.split(":", limit = 2)
        val first = parts.firstOrNull().orEmpty()
        val scale = first.toIntOrNull()
        val value = if (scale != null &&
            scale in MIN_TEXT_SCALE..MAX_TEXT_SCALE &&
            parts.size == 2
        ) {
            parts[1]
        } else {
            body
        }
        return value.takeIf { it.isNotBlank() }
    }

    /** 取文字图标的字号百分比（老数据 / 非文字图标 → 100）。 */
    fun textScaleOf(key: String?): Int {
        if (key == null || !key.startsWith("$TEXT:")) return DEFAULT_TEXT_SCALE
        val body = key.removePrefix("$TEXT:")
        val parts = body.split(":", limit = 2)
        val scale = parts.firstOrNull()?.toIntOrNull() ?: return DEFAULT_TEXT_SCALE
        return if (parts.size == 2 && scale in MIN_TEXT_SCALE..MAX_TEXT_SCALE) {
            scale
        } else {
            DEFAULT_TEXT_SCALE
        }
    }

    /** 字体库图标（Lucide / Remix / Tabler）。 */
    fun fontLibrary(key: String?): IconFontLibrary? = IconFontLibrary.fromKey(key)
}
