package com.slideindex.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 球体独立小窗（对齐 FV 的结构）：
 * 球有自己的一个「球大小」的窗口，**常驻可见**，拖动时只改窗口坐标。
 *
 * 这样拖动开始不需要任何「层切换」（display 层 GONE→VISIBLE、拆空闲层等），
 * 第一帧就能把球摆到手指处，不会出现「手指动了、球还停在原地」的停滞。
 */
internal class FloatBallIconWindow(
    private val context: Context,
    private val iconView: FloatBallIconView,
) {
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false

    private var lastLeft = -1
    private var lastTop = -1
    private var lastWidth = -1
    private var lastHeight = -1

    fun attach() {
        if (attached) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowTypes.overlayWindowType(context),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "XG:ball-icon"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            OverlayWindowTypes.ensureNoBrightnessOverride(this)
        }
        val added = runCatching { wm.addView(iconView, lp) }.isSuccess
        if (!added) return
        windowManager = wm
        layoutParams = lp
        attached = true
    }

    /** 热路径：只改窗口坐标/尺寸，位置没变直接返回。 */
    fun moveTo(left: Int, top: Int, widthPx: Int, heightPx: Int) {
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        if (left == lastLeft && top == lastTop && widthPx == lastWidth && heightPx == lastHeight) {
            return
        }
        lastLeft = left
        lastTop = top
        lastWidth = widthPx
        lastHeight = heightPx
        lp.x = left
        lp.y = top
        if (lp.width != widthPx || lp.height != heightPx) {
            lp.width = widthPx.coerceAtLeast(1)
            lp.height = heightPx.coerceAtLeast(1)
        }
        runCatching { wm.updateViewLayout(iconView, lp) }
    }

    fun setVisible(visible: Boolean) {
        iconView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun rect(): Rect = Rect(lastLeft, lastTop, lastLeft + lastWidth, lastTop + lastHeight)

    fun isAttached(): Boolean = attached

    fun dispose() {
        if (!attached) return
        attached = false
        val wm = windowManager
        runCatching { wm?.removeViewImmediate(iconView) }
        windowManager = null
        layoutParams = null
    }
}
