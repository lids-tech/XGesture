package com.slideindex.app.iconpack

import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParserFactory

/**
 * 已安装图标包的扫描与 `appfilter.xml` 解析。
 *
 * 所有方法都会碰 `PackageManager` / 跨包资源，**必须在 IO 线程调用**。
 * 解析结果按「包名 + lastUpdateTime」缓存，图标包更新后自动失效。
 */
object IconPackRepository {

    private class CachedFilter(val version: Long, val filter: IconPackFilter)

    private val filterCache = ConcurrentHashMap<String, CachedFilter>()

    fun clearCaches() {
        filterCache.clear()
        IconPackDrawableLoader.clearCache()
    }

    /** 枚举已安装的第三方标准图标包。 */
    fun scan(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val seen = LinkedHashSet<String>()

        for (action in IconPackIntents.ACTIONS) {
            runCatching {
                pm.queryIntentActivities(Intent(action), 0)
            }.getOrDefault(emptyList()).forEach { info ->
                info.activityInfo?.packageName?.let { seen.add(it) }
            }
        }
        // 少数包只声明成 launcher category
        for (category in IconPackIntents.CATEGORIES) {
            runCatching {
                pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category),
                    0,
                )
            }.getOrDefault(emptyList()).forEach { info ->
                info.activityInfo?.packageName?.let { seen.add(it) }
            }
        }

        return seen.mapNotNull { pkg ->
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                IconPackInfo(
                    packageName = pkg,
                    label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg),
                    icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** 解析并缓存某个图标包的 appfilter；解析失败返回 null。 */
    fun filter(context: Context, packPackage: String): IconPackFilter? {
        val version = runCatching {
            context.packageManager.getPackageInfo(packPackage, 0).lastUpdateTime
        }.getOrDefault(0L)
        filterCache[packPackage]?.let { cached ->
            if (cached.version == version) return cached.filter
        }
        val parsed = runCatching { parse(context, packPackage) }.getOrNull() ?: return null
        filterCache[packPackage] = CachedFilter(version, parsed)
        return parsed
    }

    /** 目标应用的启动 Activity 名，用于 appfilter 的 component 精确匹配。 */
    fun launchActivityName(context: Context, packageName: String): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.className
    }.getOrNull()

    private fun parse(context: Context, packPackage: String): IconPackFilter? {
        val packContext = context.createPackageContext(packPackage, 0)

        // 1) assets/appfilter.xml —— 原始 XML，直接读
        val fromAssets = runCatching {
            packContext.assets.open("appfilter.xml").use { input ->
                val parser = XmlPullParserFactory.newInstance()
                    .apply { isNamespaceAware = false }
                    .newPullParser()
                parser.setInput(input, "UTF-8")
                IconPackFilterParser.parse(packPackage, parser)
            }
        }.getOrNull()
        if (fromAssets != null && !fromAssets.isEmpty) return fromAssets

        // 2) res/xml/appfilter.xml —— 编译后的二进制 XML，只能走 Resources
        val fromRes = runCatching {
            val resources = packContext.resources
            val id = resources.getIdentifier("appfilter", "xml", packPackage)
            if (id == 0) return@runCatching null
            IconPackFilterParser.parse(packPackage, resources.getXml(id))
        }.getOrNull()
        if (fromRes != null && !fromRes.isEmpty) return fromRes

        // 两条路都拿到空表：把空表也返回，让 UI 能区分「解析不了」和「没图标」
        return fromAssets ?: fromRes
    }
}
