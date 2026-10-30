package com.slideindex.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 「应用图标」全局覆盖的**运行时入口**。
 *
 * 与 [ActionIconBitmap] 的分工：
 * - 动作图标的渲染入口天然收敛（所有面板最后都调 `ActionIconBitmap`），所以接一处就全覆盖；
 * - 应用图标没有这种单点：索引/任务切换器走 `AppRepository`，面板走各自的 LRU，
 *   有的直接 `pm.getApplicationIcon`。所以这里提供一个统一入口，让各渲染点
 *   「先问这里，拿不到再走原逻辑」。
 *
 * 另外 `AppRepository` 通过 `AppIconOverrideProvider` 端口也接了这里，
 * 所以走 AppRepository 的路径（索引/圆环/蜂窝/全息/轮盘）即使忘了显式调用也能生效。
 *
 * ## 作用范围（写死，不要扩大）
 *
 * 只作用于**应用启动图标**：桌面、面板、索引、最近任务里代表这个应用的那张图。
 * 以下明确**不**走这里，必要时各走各的体系：
 * - 通知栏图标（系统提供）
 * - 搜索引擎自己的图标配置
 * - 应用内 Activity 图标、快捷方式的专属图标（只有回退到宿主应用图标时才用）
 *
 * 新增取「应用启动图标」的代码**必须走这里**，不要直接调 `PackageManager.getApplicationIcon`；
 * `AppRepository` 也通过 `AppIconOverrideProvider` 端口转到这里，走它的渲染路径自动生效。
 *
 * 图标本身可能是 `file:`（相册 / 图标包落盘的 PNG）、`t:`（文字）、字体库三种，
 * 直接复用 [ActionIconBitmap.loadByKey] 的渲染。
 *
 * 缓存 key 带 [GlobalIconStore.revision]：改完图标立刻失效，不用等进程重启。
 * 应用图标**不加底板**（`withPlate = false`），否则会和系统图标的样子不一致。
 */
object AppIconOverride {

    private val cache = object : LruCache<String, Bitmap>(64) {}

    fun clear() {
        cache.evictAll()
    }

    /**
     * 只读缓存版本：**主线程安全**，不会解码。
     *
     * 给「每帧绘制」的 Canvas 路径用；未命中时返回 null，由调用方决定先画占位还是回退原图。
     */
    fun peek(
        context: Context,
        packageName: String,
        sizePx: Int,
        withPlate: Boolean = false,
    ): Bitmap? {
        if (packageName.isBlank() || sizePx <= 0) return null
        // 没有覆盖就别去碰缓存，免得白占命中率
        GlobalIconStore.appIcon(context, packageName) ?: return null
        return cache.get(cacheKey(packageName, sizePx, withPlate))
    }

    /**
     * @return 覆盖后的图标；null 表示这个应用没设过覆盖，调用方继续走原逻辑。
     *
     * **会解码 PNG，只能在 IO 线程调用**（缓存命中时很快，未命中时 1~3ms）。
     */
    fun bitmap(
        context: Context,
        packageName: String,
        sizePx: Int,
        withPlate: Boolean = false,
    ): Bitmap? {
        if (packageName.isBlank() || sizePx <= 0) return null
        val key = GlobalIconStore.appIcon(context, packageName) ?: return null
        val cacheKey = cacheKey(packageName, sizePx, withPlate)
        cache.get(cacheKey)?.let { return it }
        val dark = IconPlateModeState.isDark(context)
        val bitmap = ActionIconBitmap.loadByKey(
            context = context,
            key = key,
            sizePx = sizePx,
            tintArgb = if (dark) Color.WHITE else Color.BLACK,
            withPlate = withPlate,
            darkPlate = dark,
        ) ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap
    }

    /** Drawable 版本：给只接受 Drawable 的老渲染路径（蜂窝、全息、快捷方式等）。 */
    fun drawable(context: Context, packageName: String, sizePx: Int): Drawable? =
        bitmap(context, packageName, sizePx)?.let { BitmapDrawable(context.resources, it) }

    /** 只读缓存的 Drawable 版本，主线程安全。 */
    fun peekDrawable(context: Context, packageName: String, sizePx: Int): Drawable? =
        peek(context, packageName, sizePx)?.let { BitmapDrawable(context.resources, it) }

    private fun cacheKey(packageName: String, sizePx: Int, withPlate: Boolean): String =
        "$packageName|$sizePx|$withPlate|${GlobalIconStore.revision}"
}
