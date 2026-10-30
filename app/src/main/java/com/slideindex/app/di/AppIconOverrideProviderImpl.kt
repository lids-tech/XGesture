package com.slideindex.app.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.slideindex.app.data.AppIconOverrideProvider
import com.slideindex.app.ui.AppIconOverride
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「图标库 → 应用图标覆盖」接到 `feature/apps` 的端口上。
 *
 * 这样 `AppRepository` 的四个图标入口自带覆盖，索引 / 任务切换器 / 圆环 / 蜂窝 / 全息 /
 * 轮盘等所有走 AppRepository 的渲染路径不用各自判断；以后的新面板也一样。
 */
@Singleton
class AppIconOverrideProviderImpl @Inject constructor() : AppIconOverrideProvider {

    override fun bitmap(context: Context, packageName: String, sizePx: Int): Bitmap? =
        AppIconOverride.bitmap(context, packageName, sizePx)

    override fun peekBitmap(context: Context, packageName: String, sizePx: Int): Bitmap? =
        AppIconOverride.peek(context, packageName, sizePx)

    override fun drawable(context: Context, packageName: String, sizePx: Int): Drawable? =
        AppIconOverride.drawable(context, packageName, sizePx)

    override fun peekDrawable(context: Context, packageName: String, sizePx: Int): Drawable? =
        AppIconOverride.peekDrawable(context, packageName, sizePx)

    override fun preload(context: Context, packageNames: Collection<String>, sizePx: Int) =
        AppIconOverride.preload(context, packageNames, sizePx)
}
