package com.slideindex.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/**
 * 「应用图标全局覆盖」的端口。
 *
 * `feature/apps` 不能反向依赖 `app` 模块，所以这里只定义接口；实现在 `app` 模块
 * （委托给 `AppIconOverride`），由 Hilt 绑定。
 *
 * 端口放在 [AppRepository] 的图标入口上，是为了让**所有走 AppRepository 的渲染路径**
 * （应用索引、任务切换器、圆环、蜂窝、全息、轮盘……）自动拿到覆盖图，
 * 不必再逐个面板去接 —— 新面板只要走这几个方法就不会再踩「图标不生效」的坑。
 */
interface AppIconOverrideProvider {

    /** 允许解码，**只能在 IO 线程调用**；没有覆盖时返回 null。 */
    fun bitmap(context: Context, packageName: String, sizePx: Int): Bitmap?

    /** 只读缓存，**主线程安全**；没有覆盖或缓存未命中时返回 null。 */
    fun peekBitmap(context: Context, packageName: String, sizePx: Int): Bitmap?

    /** 允许解码的 Drawable 版本（老渲染路径只接受 Drawable）。 */
    fun drawable(context: Context, packageName: String, sizePx: Int): Drawable?

    /** 只读缓存的 Drawable 版本，主线程安全。 */
    fun peekDrawable(context: Context, packageName: String, sizePx: Int): Drawable?

    /**
     * 预热一批应用图标（IO 线程调用）。
     *
     * 主题层的图不落盘、每次从图标包取 drawable，不预热的话面板首帧会在主线程解码。
     */
    fun preload(context: Context, packageNames: Collection<String>, sizePx: Int)
}
