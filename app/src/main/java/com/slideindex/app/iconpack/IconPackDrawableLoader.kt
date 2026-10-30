package com.slideindex.app.iconpack

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * 从图标包里取一张图标并转成 Bitmap。
 *
 * 全是公开 API：`createPackageContext` + `getIdentifier` + `getDrawable`。
 * 图标包被 launcher 读取走的就是这条路，不需要 root 或隐藏接口。
 */
object IconPackDrawableLoader {

    private val resourcesCache = ConcurrentHashMap<String, Resources>()

    fun clearCache() = resourcesCache.clear()

    /**
     * @param packPackage 图标包包名
     * @param drawableName appfilter 里写的 drawable 名（可能带 .png / .xml 后缀）
     * @param targetPx 目标边长，图标会等比铺满这个正方形
     */
    fun load(
        context: Context,
        packPackage: String,
        drawableName: String,
        targetPx: Int,
    ): Bitmap? {
        val name = drawableName.trim().removeSuffix(".png").removeSuffix(".xml").trim()
        if (name.isEmpty()) return null
        val resources = resourcesOf(context, packPackage) ?: return null
        val id = runCatching {
            resources.getIdentifier(name, "drawable", packPackage)
        }.getOrDefault(0)
        if (id == 0) return null
        val drawable = runCatching { resources.getDrawable(id, null) }.getOrNull() ?: return null
        return toBitmap(drawable, targetPx)
    }

    private fun resourcesOf(context: Context, packPackage: String): Resources? {
        resourcesCache[packPackage]?.let { return it }
        return runCatching {
            context.createPackageContext(packPackage, 0).resources
        }.getOrNull()?.also { resourcesCache[packPackage] = it }
    }

    /** Drawable → 正方形 Bitmap，等比铺满。 */
    fun toBitmap(drawable: Drawable, targetPx: Int): Bitmap? {
        val size = targetPx.coerceIn(16, 1024)
        val bitmap = runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        runCatching {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }.onFailure {
            bitmap.recycle()
            return null
        }
        return bitmap
    }
}
