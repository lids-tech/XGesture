package com.slideindex.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.util.ImageDecodeGuard

/**
 * 把「动作图标」渲染成 Bitmap，供 **Canvas 绘制** 的场景使用（面板格子 / 圆环 / 蜂窝 / 轮盘）。
 *
 * 为什么需要它：那些地方是 `canvas.drawBitmap(...)`，画不了 Compose 的字体字符，
 * 只能先把字符用 Typeface 画进 Bitmap，再交给 Canvas。
 *
 * 返回 null 表示"没有全局覆盖"，调用方继续走原来的默认图标逻辑。
 */
object ActionIconBitmap {

    fun load(
        context: Context,
        action: GestureAction,
        sizePx: Int,
        tintArgb: Int = Color.WHITE,
        withPlate: Boolean = false,
    ): Bitmap? {
        val darkPlate = IconPlateModeState.isDark(context)
        // 面板图标 → 动作类型图标 → 应用图标，统一在 ActionIconKeyResolver 里判。
        val key = ActionIconKeyResolver.resolve(context, action) ?: return null
        return loadByKey(context, key, sizePx, tintArgb, withPlate, action, darkPlate)
    }

    fun loadByKey(
        context: Context,
        key: String,
        sizePx: Int,
        tintArgb: Int = Color.WHITE,
        withPlate: Boolean = false,
        action: GestureAction? = null,
        darkPlate: Boolean = true,
    ): Bitmap? {
        if (sizePx <= 0) return null
        // 1) 图片文件
        if (key.startsWith("file:")) {
            val path = key.removePrefix("file:")
            val raw = ImageDecodeGuard.decodeFile(path, sizePx) ?: return null
            return Bitmap.createScaledBitmap(raw, sizePx, sizePx, true)
        }
        // 2) 文字图标
        if (key.startsWith("t:")) {
            return renderText(
                text = ActionIconKeyCodec.textOf(key).orEmpty(),
                sizePx = sizePx,
                typeface = null,
                tintArgb = tintArgb,
                withPlate = withPlate,
                action = action,
                darkPlate = darkPlate,
                scalePercent = ActionIconKeyCodec.textScaleOf(key),
            )
        }
        // 3) 字体图标
        val library = IconFontLibrary.fromKey(key) ?: return null
        val name = key.removePrefix(library.keyPrefix)
        val item = IconFontCatalog.entries(context, library).firstOrNull { it.name == name }
            ?: return null
        val typeface = runCatching { ResourcesCompat.getFont(context, library.fontRes) }.getOrNull()
            ?: return null
        return renderText(
            String(Character.toChars(item.codePoint)),
            sizePx,
            typeface,
            tintArgb,
            withPlate,
            action,
            darkPlate,
        )
    }

    private fun renderText(
        text: String,
        sizePx: Int,
        typeface: Typeface?,
        tintArgb: Int,
        withPlate: Boolean,
        action: GestureAction?,
        darkPlate: Boolean = true,
        scalePercent: Int = ActionIconKeyCodec.DEFAULT_TEXT_SCALE,
    ): Bitmap? {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 与内置动作图标一致的"黑底方块"，否则自定义图标没有底板、线条也不变白
        if (withPlate) {
            val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 用该动作类型的内置底板渐变，而不是死黑，否则和旁边默认图标不协调
                if (action != null) {
                    val (start, end) = com.slideindex.app.util.GestureActionIconBitmap
                        .plateGradient(action, darkPlate)
                    shader = android.graphics.LinearGradient(
                        0f, 0f, 0f, sizePx.toFloat(),
                        start, end,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    color = Color.argb(0xE6, 0x1C, 0x1C, 0x1E)
                }
            }
            val corner = sizePx * 0.22f
            canvas.drawRoundRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), corner, corner, platePaint)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // 带底板时：暗色底板配浅色字，亮色底板配黑字；
            // 不带底板（文字图标）时直接用调用方给的 tint —— 用户设了红色就该是红字。
            color = when {
                !withPlate -> tintArgb
                darkPlate -> tintArgb
                else -> Color.BLACK
            }
            // 与内置图标一致：带底时占 0.54，裸图标占 0.75（见 GestureActionIconBitmap）
            // 带底板占 0.54；无底板的文字图标原来 0.75 偏大，缩到 0.58（真机反馈"字体有点大"）。
            textSize = sizePx * (if (withPlate) 0.54f else 0.58f) * (scalePercent / 100f)
            // 多字自动缩小：宽出图标框就往下调（文字图标已放开字数，长文本靠自动缩小完整显示）。
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        val maxWidth = sizePx * 0.92f
        var fitSize = paint.textSize
        while (fitSize > sizePx * 0.12f && paint.measureText(text) > maxWidth) {
            fitSize -= sizePx * 0.02f
            paint.textSize = fitSize
        }
        val baseline = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, sizePx / 2f, baseline, paint)
        return bitmap
    }
}
