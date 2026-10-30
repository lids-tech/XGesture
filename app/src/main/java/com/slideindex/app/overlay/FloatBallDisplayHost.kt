package com.slideindex.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView

/**
 * Display 层根容器：只画线条（Compose）和取词准星（原生 View）。
 *
 * 球体已经拆到独立的 [FloatBallIconWindow]（球大小的小窗、常驻），
 * 拖动时只改那个窗口的坐标，避免这一整层在拖动开始时被切换可见性。
 */
@SuppressLint("ViewConstructor")
internal class FloatBallDisplayHost(
    context: Context,
    private val lineChromeOwner: OverlayComposeOwner,
    val cursorPreviewView: FloatBallCursorPreviewView
) : FrameLayout(context) {

    val lineChromeComposeView: ComposeView

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren = false
        OverlayCompose.bindOwners(this, lineChromeOwner)

        lineChromeComposeView = OverlayCompose.createComposeView(context, lineChromeOwner)
        addView(lineChromeComposeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        cursorPreviewView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(cursorPreviewView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val fullWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val fullHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        lineChromeComposeView.measure(fullWidthSpec, fullHeightSpec)
        cursorPreviewView.measure(fullWidthSpec, fullHeightSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        lineChromeComposeView.layout(0, 0, width, height)
        cursorPreviewView.layout(0, 0, width, height)
    }

    fun dispose() {
        OverlayCompose.teardownOverlayCompose(lineChromeComposeView, lineChromeOwner)
    }
}
