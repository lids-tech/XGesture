package com.slideindex.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.io.File

/**
 * 动图图标支持（GIF / 动画 WebP）。
 *
 * 背景：自定义图标走的是"解码成 Bitmap"的老路，GIF/WebP 只剩第一帧 ——
 * 用户要的是**动图能真的动起来**。
 *
 * 做法：文件图标里 GIF/WebP 用 [android.graphics.ImageDecoder] 解成
 * [AnimatedImageDrawable]（API 28+，本工程 minSdk 31 安全），循环播放；
 * 其它格式/静态图仍然走原来的 Bitmap 路径。
 */
object AnimatedIconSupport {

    /** 可能是动图的后缀（实际是不是动图由 ImageDecoder 判定）。 */
    fun looksAnimated(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".apng")
    }

    /**
     * 解成 Drawable：动图返回 [AnimatedImageDrawable]（已开始播放、无限循环）；
     * 静态图返回 [BitmapDrawable]。失败返回 null。
     */
    fun decode(context: Context, path: String): Drawable? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            val source = android.graphics.ImageDecoder.createSource(file)
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source)
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
            drawable
        }.getOrNull()
    }

    /** 只解第一帧的 Bitmap（给 HUD / 二级菜单这类静态小图用）。 */
    fun firstFrameBitmap(context: Context, path: String, maxSidePx: Int): Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            val source = android.graphics.ImageDecoder.createSource(File(path))
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(maxSidePx, maxSidePx)
            }
        }.getOrNull()
    }

    /**
     * 把图标贴到 ImageView 上：动图用 Drawable（自动播放），静态图也统一走 Drawable。
     */
    fun applyTo(imageView: ImageView, context: Context, path: String): Boolean {
        val drawable = decode(context, path) ?: return false
        imageView.setImageDrawable(drawable)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        return true
    }
}
