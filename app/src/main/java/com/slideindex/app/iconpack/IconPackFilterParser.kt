package com.slideindex.app.iconpack

import java.io.StringReader
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * `appfilter.xml` 解析器。
 *
 * 格式是 ADW / Nova 时代定下的约定，没有官方规范，所以这里对每个字段都做容错：
 * - 标签名大小写不敏感
 * - `component` 允许 `ComponentInfo{pkg/cls}`、`pkg/cls`、`pkg/.cls` 三种写法
 * - `iconback` 可能有 `img` / `img1` / `img2` 多个属性
 * - `scale` 的 factor 限制在 0.1~2.0（与 launcher 实现一致）
 *
 * 纯函数，不碰 Android API，可以直接用字符串做单元测试。
 */
object IconPackFilterParser {

    fun parse(packageName: String, xml: String): IconPackFilter {
        val parser = XmlPullParserFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newPullParser()
        parser.setInput(StringReader(xml))
        return parse(packageName, parser)
    }

    fun parse(packageName: String, parser: XmlPullParser): IconPackFilter {
        val componentMap = LinkedHashMap<String, String>()
        val packageMap = LinkedHashMap<String, String>()
        val allDrawables = LinkedHashSet<String>()
        val iconBacks = ArrayList<String>()
        var iconMask: String? = null
        var iconUpon: String? = null
        var scale = 1f

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name?.lowercase(Locale.ROOT)) {
                    "item" -> {
                        val component = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (!component.isNullOrBlank() && !drawable.isNullOrBlank()) {
                            val name = drawable.trim()
                            allDrawables.add(name)
                            val split = splitComponent(component)
                            if (split != null) {
                                val key = "${split.first}/${split.second}"
                                if (!componentMap.containsKey(key)) componentMap[key] = name
                                if (!packageMap.containsKey(split.first)) packageMap[split.first] = name
                            }
                        }
                    }

                    "iconback" -> collectImg(parser, iconBacks)
                    "iconmask" -> if (iconMask == null) iconMask = firstImg(parser)
                    "iconupon" -> if (iconUpon == null) iconUpon = firstImg(parser)

                    "scale" -> {
                        val factor = parser.getAttributeValue(null, "factor")?.toFloatOrNull()
                        if (factor != null && factor in 0.1f..2.0f) scale = factor
                    }
                }
            }
            event = parser.next()
        }

        return IconPackFilter(
            packageName = packageName,
            componentMap = componentMap,
            packageMap = packageMap,
            allDrawables = allDrawables.toList(),
            scaleFactor = scale,
            iconBacks = iconBacks,
            iconMask = iconMask,
            iconUpon = iconUpon,
        )
    }

    /** `<iconback img="a" img2="b"/>` 这类可能带多个 img 属性的标签。 */
    private fun collectImg(parser: XmlPullParser, out: MutableList<String>) {
        for (i in 0 until parser.attributeCount) {
            val attr = parser.getAttributeName(i)?.lowercase(Locale.ROOT) ?: continue
            if (!attr.startsWith("img")) continue
            val value = parser.getAttributeValue(i)?.trim()
            if (!value.isNullOrEmpty()) out.add(value)
        }
    }

    private fun firstImg(parser: XmlPullParser): String? {
        for (i in 0 until parser.attributeCount) {
            val attr = parser.getAttributeName(i)?.lowercase(Locale.ROOT) ?: continue
            if (!attr.startsWith("img")) continue
            val value = parser.getAttributeValue(i)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    /**
     * 拆 `ComponentInfo{com.foo/.Main}` 为 `com.foo` + `com.foo.Main`。
     *
     * 返回 null 表示这一条没法用，跳过即可，不要影响整包解析。
     */
    internal fun splitComponent(raw: String): Pair<String, String>? {
        val inner = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = inner.indexOf('/')
        if (slash <= 0 || slash >= inner.length - 1) return null
        val pkg = inner.substring(0, slash).trim()
        var cls = inner.substring(slash + 1).trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null
        if (cls.startsWith(".")) cls = pkg + cls
        if (cls.contains('/') || cls.contains('{') || cls.contains('}')) return null
        return pkg to cls
    }
}
