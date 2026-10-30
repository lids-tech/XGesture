package com.slideindex.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PickerAppIconBitmap {
    // 挑应用网格里的图标按 58dp 渲染，3x 屏是 174px，这里取 176px 留余量。
    // 单条约 124KB（176² × 4B），缓存顶满 256 条约 32MB；实际条目数通常 100~200。
    private const val ICON_SIZE_PX = 176

    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(48, 256),
    ) {}

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    fun peekActivity(packageName: String, className: String): ImageBitmap? =
        cache.get(activityCacheKey(packageName, className))

    fun putActivityIcon(packageName: String, className: String, drawable: Drawable) {
        val key = activityCacheKey(packageName, className)
        if (cache.get(key) != null) return
        cache.put(key, drawable.toSafeImageBitmap(ICON_SIZE_PX))
    }

    suspend fun load(context: Context, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            cache.get(packageName)?.let { return@withContext it }
            val pm = context.applicationContext.packageManager
            val drawable = try {
                pm.getApplicationIcon(packageName)
            } catch (_: Exception) {
                return@withContext null
            }
            val bitmap = drawable.toSafeImageBitmap(ICON_SIZE_PX)
            cache.put(packageName, bitmap)
            bitmap
        }

    /** 优先 Activity 图标，失败回退应用图标。 */
    suspend fun loadActivity(context: Context, packageName: String, className: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val key = activityCacheKey(packageName, className)
            cache.get(key)?.let { return@withContext it }
            val pm = context.applicationContext.packageManager
            val component = ComponentName(packageName, className)
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            val drawable = runCatching { pm.getActivityIcon(component) }.getOrNull()
                ?: runCatching { pm.getActivityInfo(component, flags).loadIcon(pm) }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                ?: return@withContext null
            val bitmap = drawable.toSafeImageBitmap(ICON_SIZE_PX)
            cache.put(key, bitmap)
            bitmap
        }

    fun clear() {
        cache.evictAll()
    }

    private fun activityCacheKey(packageName: String, className: String): String =
        "$packageName/$className"
}
