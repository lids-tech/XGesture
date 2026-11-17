package com.slideindex.app.widget

import android.graphics.Bitmap

/**
 * 小组件面板的「操作提示条」（HUD）。
 *
 * 与快捷轮盘的 HUD 同一套视觉语言：屏幕上方固定一条深色胶囊，左边是当前操作图标，
 * 中间是名称 + 一句"松手会怎样"，还没按满时长按进度画成绕图标的一圈环。
 *
 * ⚠️ 这里**刻意不用 Compose**：面板本身是纯 View 的浮层窗口，没有
 * ViewTreeLifecycleOwner，往里面塞 ComposeView 会在 attach 时抛
 * "ViewTreeLifecycleOwner not found"（打开面板直接闪退）。
 * HUD 只需要画几笔，直接由面板根布局的 `dispatchDraw` 绘制最稳。
 */
data class WidgetPanelHudState(
    val title: String,
    val subtitle: String? = null,
    val icon: Bitmap? = null,
    /** 变化即重启长按进度（换到另一项时要从头开始）。 */
    val progressKey: Any? = null,
    val longPressMs: Int,
    val showProgress: Boolean = true,
)

object WidgetPanelHud {

    @Volatile
    var state: WidgetPanelHudState? = null
        private set

    /** 当前进度的起点（换项时重置）。 */
    @Volatile
    private var progressStartMs: Long = 0L

    @Volatile
    private var lastProgressKey: Any? = null

    /** 状态变化时的回调（由面板根布局注册：收到就 invalidate）。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun show(next: WidgetPanelHudState) {
        if (next.progressKey != lastProgressKey) {
            progressStartMs = System.currentTimeMillis()
            lastProgressKey = next.progressKey
        }
        state = next
        onChanged?.invoke()
    }

    fun clear() {
        if (state == null) return
        state = null
        lastProgressKey = null
        progressStartMs = 0L
        onChanged?.invoke()
    }

    /** 长按进度 0..1（按状态里的 longPressMs 线性推进）。 */
    fun progress(nowMs: Long = System.currentTimeMillis()): Float {
        val current = state ?: return 0f
        if (!current.showProgress) return 1f
        if (progressStartMs <= 0L) return 0f
        val total = current.longPressMs.coerceAtLeast(1).toFloat()
        return ((nowMs - progressStartMs) / total).coerceIn(0f, 1f)
    }
}
