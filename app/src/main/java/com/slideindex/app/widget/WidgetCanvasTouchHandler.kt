package com.slideindex.app.widget

import android.view.MotionEvent
import androidx.core.view.ViewCompat
import kotlin.math.abs

internal class WidgetCanvasTouchHandler(
    private val layout: WidgetCanvasLayout,
) {
    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val chromeTarget = layout.chromeTouchTarget
        if (chromeTarget != null) {
            val handled = layout.deliverTouchToChild(chromeTarget, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                layout.chromeTouchTarget = null
            }
            return handled
        }

        if (layout.editMode && layout.draggingChild == null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val child = layout.findTouchTarget(event.x, event.y)
            if (child != null) {
                val localX = event.x - child.left - child.translationX
                val localY = event.y - child.top - child.translationY
                if (child.isTouchOnChrome(localX, localY)) {
                    layout.chromeTouchTarget = child
                    return layout.deliverTouchToChild(child, event)
                }
            }
        }
        if (layout.editMode && layout.draggingChild == null) {
            if (layout.panelScrollActive) {
                return onTouchEvent(event)
            }
            if (shouldDelegateWidgetBodyTouch(event)) {
                return layout.superDispatchTouchEvent(event)
            }
            return handleEditModePanelTouch(event)
        }
        if (layout.draggingChild != null) {
            return onTouchEvent(event)
        }
        if (!layout.editMode) {
            if (handleBrowseModeTouch(event)) return true
        }
        val handled = layout.superDispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            WidgetPanelProbe.gestureDown(
                editMode = layout.editMode,
                foundCard = layout.findTouchTarget(event.x, event.y) != null,
                handled = handled,
            )
        }
        if (!handled) {
            // 画布没接住这次手势 → 它不在触摸目标链里，后续 MOVE/UP/CANCEL 都不会送达，
            // 长按计时器再没有机会被取消；必须在这里立刻撤销，否则 500ms 后会误进编辑态。
            cancelBrowseLongPress()
        }
        return handled
    }

    fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (layout.draggingChild != null) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layout.panelScrollDownX = event.x
                layout.panelScrollDownY = event.y
                layout.panelScrollLastY = event.y
                layout.panelScrollActive = false
                layout.panelScrollTouchTarget = layout.findTouchTarget(event.x, event.y)
                if (!layout.editMode) return false
                val child = layout.panelScrollTouchTarget
                if (child != null) {
                    val localX = event.x - child.left - child.translationX
                    val localY = event.y - child.top - child.translationY
                    if (!child.isTouchOnChrome(localX, localY)) {
                        scheduleWidgetDragLongPress(child, event.x, event.y)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (layout.editMode) {
                    if (layout.pendingDragChild != null && layout.draggingChild == null) {
                        val dx = event.x - layout.pendingDragDownX
                        val dy = event.y - layout.pendingDragDownY
                        if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                            cancelPendingDrag()
                        }
                    }
                    if (maybeStartPanelScroll(event)) {
                        return true
                    }
                    return layout.draggingChild != null
                }
                if (maybeStartPanelScroll(event)) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingDrag()
                layout.panelScrollTouchTarget = null
                if (layout.panelScrollActive) {
                    stopPanelScroll()
                    return true
                }
            }
        }
        return layout.draggingChild != null || layout.panelScrollActive
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (layout.panelScrollActive && layout.draggingChild == null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> return dispatchPanelNestedScroll(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPanelScroll()
                    return true
                }
            }
        }

        if (layout.draggingChild != null) {
            val child = layout.draggingChild!!
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val left = event.x - layout.dragTouchOffsetX
                    val top = event.y - layout.dragTouchOffsetY
                    child.translationX = left - child.left
                    child.translationY = top - child.top
                    val oldHoverX = layout.hoverCellX
                    val oldHoverY = layout.hoverCellY
                    WidgetCanvasLayoutGeometry.updateHoverCell(layout, event.x, event.y)
                    if (oldHoverX != layout.hoverCellX || oldHoverY != layout.hoverCellY) {
                        layout.applyDragLiveReorderPreview()
                    }
                    layout.invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(child)
            }
            return true
        }

        if (!layout.editMode) {
            if (handleBlankAreaTouch(event)) return true
        }
        return false
    }

    fun cancelPendingDrag() {
        layout.pendingDragLongPress?.let { layout.removeCallbacks(it) }
        layout.pendingDragLongPress = null
        layout.pendingDragChild = null
    }

    fun cancelBrowseLongPress() {
        layout.pendingBrowseLongPress?.let { layout.removeCallbacks(it) }
        layout.pendingBrowseLongPress = null
        layout.browseTouchChild = null
        layout.browseTouchTracking = false
        layout.browseLongPressConsumed = false
    }

    /** 取消「长按弹二级菜单」的待触发计时（手指一移动就取消，避免误弹）。 */
    fun cancelSubMenuLongPress() {
        layout.pendingSubMenuLongPress?.let { layout.removeCallbacks(it) }
        layout.pendingSubMenuLongPress = null
        layout.subMenuTouchTracking = false
    }

    /**
     * 没配二级菜单的格子：长按 = 「上膛」（震动提示），松手以小窗打开 /
     * 按原方式执行。手指移出 touchSlop 就取消，避免拖着拖着突然上膛。
     */
    private fun scheduleNoMenuLongPress(child: WidgetCardContainer, x: Float, y: Float) {
        cancelNoMenuLongPress()
        layout.noMenuArmedChild = null
        layout.noMenuTracking = true
        layout.noMenuTouchDownX = x
        layout.noMenuTouchDownY = y
        val runnable = Runnable {
            if (!layout.noMenuTracking || layout.editMode) return@Runnable
            if (layout.findTouchTarget(layout.noMenuTouchDownX, layout.noMenuTouchDownY) !== child) {
                return@Runnable
            }
            layout.noMenuTracking = false
            layout.noMenuArmedChild = child
            layout.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        layout.noMenuPendingRunnable = runnable
        layout.postDelayed(runnable, layout.longPressTimeout)
    }

    fun cancelNoMenuLongPress() {
        layout.noMenuPendingRunnable?.let { layout.removeCallbacks(it) }
        layout.noMenuPendingRunnable = null
        layout.noMenuTracking = false
        layout.noMenuArmedChild = null
    }

    /**
     * 长按应用 / 动作 / 快捷方式格子 → 弹二级菜单。
     *
     * 松手时：指在某一项上 = 执行该动作；指在空处 = 走该格子的默认点击
     * （应用 = 小窗打开）。没配二级菜单的格子不会走到这里。
     */
    private fun scheduleSubMenuLongPress(child: WidgetCardContainer, x: Float, y: Float) {
        cancelSubMenuLongPress()
        layout.subMenuTouchChild = child
        layout.subMenuTouchDownX = x
        layout.subMenuTouchDownY = y
        layout.subMenuTouchTracking = true
        layout.pendingSubMenuLongPress = Runnable {
            if (!layout.subMenuTouchTracking || layout.editMode) return@Runnable
            val target = layout.subMenuTouchChild ?: return@Runnable
            if (layout.findTouchTarget(layout.subMenuTouchDownX, layout.subMenuTouchDownY) !== target) {
                return@Runnable
            }
            layout.subMenuTouchTracking = false
            layout.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // ⚠️ 必须先给卡片发 CANCEL 再弹菜单：
            // 应用 / 快捷方式格子的内容视图是可点的，DOWN 之后它成了触摸目标，
            // 后续 MOVE/UP 都直接送给它，画布根本收不到 → 菜单不会跟着手指高亮、
            // 松手也不会结算。CANCEL 之后触摸目标回到画布（onTouchEvent），
            // 与「长按小组件边框进编辑」那套完全相同的处理。
            val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            layout.deliverTouchToChild(target, cancel)
            cancel.recycle()
            layout.showSubMenu(target)
            layout.subMenu?.let { state ->
                state.hovered = layout.subMenuHitAt(layout.subMenuTouchDownX, layout.subMenuTouchDownY)
                layout.invalidate()
            }
        }
        layout.postDelayed(layout.pendingSubMenuLongPress!!, layout.longPressTimeout)
    }

    /**
     * 二级菜单松手在空处：应用走小窗，其余保持该格子原有行为。
     *
     * 小窗通道不可用（没配悬浮窗权限 / 分身应用）时回落到普通点击。
     */
    /**
     * 执行二级菜单里选中的动作。
     *
     * ⚠️ 必须走 [com.slideindex.app.overlay.panels.PanelRuntimeHooks.gestureActionRunner]
     * （与快捷轮盘 / 浮球同一套执行器）：直接调 `SlideIndexAccessibilityService.perform`
     * 时，「启动应用 / 启动快捷方式」这类要拉起 Activity 的动作会被后台启动限制静默拦掉，
     * 而开关类动作不需要窗口所以看起来正常——正是真机上「动作能执行、应用和快捷方式没反应」。
     */
    private fun runSubAction(action: com.slideindex.app.gesture.GestureAction) {
        val context = layout.canvasHostContext ?: layout.context
        val settings = com.slideindex.app.overlay.panels.PanelRuntimeHooks.settingsSnapshot?.invoke(context)
        val runner = com.slideindex.app.overlay.panels.PanelRuntimeHooks.gestureActionRunner
            ?: com.slideindex.app.overlay.panels.PanelRuntimeHooks.defaultGestureRunner
        com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
        if (settings != null && runner != null) {
            runCatching { runner.run(action, settings, false, null, null, null) }
                .onFailure { android.util.Log.e("WidgetPanelSubActions", "run failed", it) }
        } else {
            com.slideindex.app.service.SlideIndexAccessibilityService.perform(action)
        }
    }

    /**
     * 「停在某一项上长按 → 该项以小窗打开」。
     *
     * 只有「启动应用」能开小窗；快捷方式 / 其它动作没有窗口概念，长按时
     * 退回普通执行（和短视频一样先给一次触觉反馈，避免以为没识别）。
     */
    private fun scheduleSubMenuItemLongPress(
        state: WidgetCanvasLayout.SubMenuState,
        index: Int,
    ) {
        layout.cancelSubMenuLongPress()
        layout.subMenuLongPressIndex = index
        val runnable = Runnable {
            if (layout.subMenu !== state) return@Runnable
            if (state.hovered != index) return@Runnable
            // 只"上膛"不执行：真正的动作留到**松手**时再做，
            // 这样用户在长按后还能反悔（移开 / 抬手取消）。
            state.longPressConsumed = true
            layout.subMenuLongPressFired = true
            layout.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            layout.invalidate()
        }
        layout.subMenuLongPressRunnable = runnable
        layout.postDelayed(runnable, layout.longPressTimeout)
    }

    /**
     * 「停在某一项上长按 → 松手以小窗打开」。
     *
     * ⚠️ 全部交给 `gestureActionRunner.run(action, ..., longPressArmed = true)`：
     * 执行器里 `shouldLaunchFullscreen(longPressArmed)` → `launchShortcutInFreeWindow`
     * 这条链对**应用和快捷方式**都做过各家 ROM 的适配（这正是长按呼出轮盘的"长按=小窗"
     * 行为本身），自己再拼 ActivityOptions 只会在部分机型上失效。
     */
    private fun openSubActionInFreeWindow(action: com.slideindex.app.gesture.GestureAction) {
        val context = layout.canvasHostContext ?: layout.context
        val settings = com.slideindex.app.overlay.panels.PanelRuntimeHooks.settingsSnapshot?.invoke(context)
        val runner = com.slideindex.app.overlay.panels.PanelRuntimeHooks.gestureActionRunner
            ?: com.slideindex.app.overlay.panels.PanelRuntimeHooks.defaultGestureRunner
        // 先收面板再执行：执行器拉起 Activity 时需要前台窗口上下文。
        com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
        if (settings != null && runner != null) {
            runCatching { runner.run(action, settings, true, null, null, null) }
                .onFailure { android.util.Log.e("WidgetPanelSubActions", "free-window run failed", it) }
        } else {
            runSubAction(action)
        }
    }

    private fun openCellDefault(child: WidgetCardContainer) {
        val item = child.item
        val context = layout.canvasHostContext ?: layout.context
        if (item.itemType == ITEM_TYPE_APP && item.cloneUserId == 0 && item.packageName.isNotEmpty()) {
            val settings = com.slideindex.app.overlay.panels.PanelRuntimeHooks.settingsSnapshot?.invoke(context)
            val launchFreeWindow = com.slideindex.app.overlay.panels.PanelRuntimeHooks.launchFreeWindow
            if (settings != null && launchFreeWindow != null) {
                // ⚠️ 先起小窗再收面板：先收面板会让这次启动变成"无可见窗口"的后台启动，
                // 部分 ROM 直接拦掉（表现就是"长按松手什么也没发生"）。
                val launched = runCatching {
                    launchFreeWindow(context, item.packageName, settings, null)
                    true
                }.getOrDefault(false)
                if (launched) {
                    com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
                    return
                }
            }
        }
        child.performContentClick()
    }

    fun cancelPendingLongPress() {
        layout.pendingLongPress?.let { layout.removeCallbacks(it) }
        layout.pendingLongPress = null
    }

    /** 撤销尚未触发的全部计时器（页面被摘除、或手势已确认丢失时调用）。 */
    fun cancelPendingTimers() {
        cancelPendingLongPress()
        cancelPendingDrag()
        cancelBrowseLongPress()
        layout.panelScrollActive = false
        layout.blankTouchTracking = false
        layout.chromeTouchTarget = null
    }

    fun resetTouchInteractionState() {
        cancelPendingLongPress()
        cancelPendingDrag()
        cancelBrowseLongPress()
        layout.blankTouchTracking = false
        layout.chromeTouchTarget = null
        layout.panelScrollActive = false
        layout.panelScrollTouchTarget = null
        stopPanelScroll()
        layout.requestDisallowInterceptAllParents(false)
        layout.resetAllChildrenLiveTranslations()
        layout.dragStartPage = null
        if (layout.draggingChild != null) {
            val child = layout.draggingChild!!
            child.animate().cancel()
            child.scaleX = 1f
            child.scaleY = 1f
            child.alpha = 1f
            child.translationZ = 0f
            child.translationX = 0f
            child.translationY = 0f
            layout.draggingChild = null
            layout.draggingItem = null
            layout.hoverCellX = -1
            layout.hoverCellY = -1
        }
        if (layout.canvasInteractionActive) {
            layout.updateInteractionActive(false)
            layout.post { layout.refreshAllWidgetLayouts() }
        }
    }

    private fun scheduleBrowseLongPress(child: WidgetCardContainer, x: Float, y: Float) {
        cancelBrowseLongPress()
        // 只允许「小组件卡」用长按进入编辑（要拖动 / 调跨度）；
        // 应用 / 动作 / 快捷方式格子不进编辑——真机反馈"长按应用也进编辑了，很容易误触"，
        // 这类格子要动它们只能长按**空白处**进编辑后再拖。
        if (child.item.itemType != ITEM_TYPE_WIDGET) {
            return
        }
        // 点在小组件自己的可交互内容上时，不抢长按：那会把这次点击取消掉
        // （真机表现为「按住稍久一点就点了没反应」）。空白/边框仍保留长按进编辑。
        if (child.shouldReceiveTouchAtPoint(x - child.left - child.translationX, y - child.top - child.translationY)) {
            return
        }
        layout.browseTouchChild = child
        layout.browseTouchDownX = x
        layout.browseTouchDownY = y
        layout.browseTouchTracking = true
        layout.pendingBrowseLongPress = Runnable {
            if (!layout.browseTouchTracking || layout.editMode) return@Runnable
            val target = layout.browseTouchChild ?: return@Runnable
            if (layout.findTouchTarget(layout.browseTouchDownX, layout.browseTouchDownY) !== target) return@Runnable
            val localX = layout.browseTouchDownX - target.left - target.translationX
            val localY = layout.browseTouchDownY - target.top - target.translationY
            if (target.shouldReceiveTouchAtPoint(localX, localY)) {
                cancelBrowseLongPress()
                return@Runnable
            }
            layout.browseLongPressConsumed = true
            layout.browseTouchTracking = false
            val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            layout.deliverTouchToChild(target, cancel)
            cancel.recycle()
            layout.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            layout.onLongPressBlank?.invoke()
        }
        layout.postDelayed(layout.pendingBrowseLongPress!!, layout.longPressTimeout)
    }

    private fun handleBrowseModeTouch(event: MotionEvent): Boolean {
        if (layout.editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 上一次手势若在别的容器里结束，这个标记可能残留；新手势开始先清账。
                layout.browseLongPressConsumed = false
                layout.subMenuLongPressFired = false
                layout.dismissSubMenu()
                val child = layout.findTouchTarget(event.x, event.y)
                if (child == null) {
                    cancelBrowseLongPress()
                    cancelSubMenuLongPress()
                } else if (child.item.itemType == ITEM_TYPE_WIDGET) {
                    // 小组件卡：长按边框仍进编辑（要能拖动 / 调跨度）。
                    cancelSubMenuLongPress()
                    scheduleBrowseLongPress(child, event.x, event.y)
                } else if (child.item.subActions.isNotEmpty()) {
                    // 应用 / 动作 / 快捷方式：长按弹二级菜单，不再进编辑。
                    cancelBrowseLongPress()
                    cancelNoMenuLongPress()
                    scheduleSubMenuLongPress(child, event.x, event.y)
                } else {
                    // 没配二级菜单：长按 = 小窗打开（应用）/ 按原方式执行。
                    cancelBrowseLongPress()
                    cancelSubMenuLongPress()
                    scheduleNoMenuLongPress(child, event.x, event.y)
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                // 二级菜单开着：只更新"指着哪一项"，并把事件吃掉，
                // 别再让下面的格子收到移动（否则会触发它们自己的滑动逻辑）。
                layout.subMenu?.let { state ->
                    val hit = layout.subMenuHitAt(event.x, event.y)
                    if (hit != state.hovered) {
                        state.hovered = hit
                        layout.invalidate()
                    }
                    // 菜单弹出后手指移动过（超过 touchSlop）→ 松手在空白处只收菜单。
                    if (!state.moved) {
                        val dx = event.x - layout.subMenuTouchDownX
                        val dy = event.y - layout.subMenuTouchDownY
                        if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                            state.moved = true
                        }
                    }
                    // 「停在某一项上不动 → 该项以小窗打开」，移动离开就取消计时。
                    if (hit >= 0 && !state.longPressConsumed) {
                        if (layout.subMenuLongPressIndex != hit) {
                            scheduleSubMenuItemLongPress(state, hit)
                        }
                    } else {
                        layout.cancelSubMenuLongPress()
                    }
                    return true
                }
                // 没配菜单的长按：手指一移动就取消"上膛"，避免误触发。
                if (layout.noMenuTracking) {
                    val dx = event.x - layout.noMenuTouchDownX
                    val dy = event.y - layout.noMenuTouchDownY
                    if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                        cancelNoMenuLongPress()
                    }
                }
                if (!layout.browseTouchTracking) return false
                val dx = event.x - layout.browseTouchDownX
                val dy = event.y - layout.browseTouchDownY
                if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                    cancelBrowseLongPress()
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val state = layout.subMenu
                if (state != null) {
                    val child = layout.subMenuTouchChild
                    val hit = state.hovered
                    val longPressFired = layout.subMenuLongPressFired
                    layout.dismissSubMenu()
                    cancelSubMenuLongPress()
                    layout.subMenuLongPressFired = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && child != null) {
                        val action = state.items.getOrNull(hit)?.action
                        when {
                            // 长按已"上膛"：松手执行小窗
                            longPressFired && action != null -> openSubActionInFreeWindow(action)
                            action != null -> runSubAction(action)
                            // 空白处：没移动过 = 只是长按了这个格子，按原方式打开；
                            // 移动过 = 用户在找菜单项没找到 → 只收菜单，什么都不做。
                            !state.moved -> openCellDefault(child)
                            else -> Unit
                        }
                    }
                    layout.subMenuTouchChild = null
                    return true
                }
                if (layout.browseLongPressConsumed) {
                    layout.browseLongPressConsumed = false
                    cancelBrowseLongPress()
                    return true
                }
                // 没配菜单的格子：长按已"上膛" → 松手按原方式打开（应用 = 小窗）。
                val armed = layout.noMenuArmedChild
                cancelNoMenuLongPress()
                if (event.actionMasked == MotionEvent.ACTION_UP && armed != null) {
                    openCellDefault(armed)
                    return true
                }
                cancelBrowseLongPress()
                cancelSubMenuLongPress()
                return false
            }
        }
        return false
    }

    private fun stopPanelScroll() {
        if (layout.panelScrollActive || layout.nestedScrollChildHelper.hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
            layout.nestedScrollChildHelper.stopNestedScroll(ViewCompat.TYPE_TOUCH)
        }
        layout.panelScrollActive = false
    }

    private fun dispatchPanelNestedScroll(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = (layout.panelScrollLastY - event.y).toInt()
                layout.panelScrollLastY = event.y
                if (dy == 0) return layout.panelScrollActive
                val consumed = IntArray(2)
                layout.nestedScrollChildHelper.dispatchNestedPreScroll(
                    0,
                    dy,
                    consumed,
                    null,
                    ViewCompat.TYPE_TOUCH,
                )
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopPanelScroll()
                return layout.panelScrollActive
            }
        }
        return false
    }

    private fun maybeStartPanelScroll(event: MotionEvent): Boolean {
        // 二级菜单开着时**绝不**开始滚动：否则一竖滑面板就滚，滚动又给画布发 CANCEL，
        // 菜单被收掉（真机反馈「上下滑菜单直接消失」）。
        if (layout.subMenu != null) return false
        val dx = event.x - layout.panelScrollDownX
        val dy = event.y - layout.panelScrollDownY
        val isOverWidget = layout.panelScrollTouchTarget != null
        val slop = if (isOverWidget) layout.touchSlop * 2.2f else layout.touchSlop.toFloat()
        if (dx * dx + dy * dy <= slop * slop) return false
        if (abs(dy) <= abs(dx)) return false
        if (shouldDeferPanelScrollToWidget(dy)) return false
        cancelPendingDrag()
        cancelPendingLongPress()
        cancelBrowseLongPress()
        layout.blankTouchTracking = false
        if (!layout.panelScrollActive) {
            layout.panelScrollActive = layout.nestedScrollChildHelper.startNestedScroll(
                ViewCompat.SCROLL_AXIS_VERTICAL,
                ViewCompat.TYPE_TOUCH,
            )
        }
        return layout.panelScrollActive
    }

    private fun handleEditModePanelTouch(event: MotionEvent): Boolean {
        if (layout.draggingChild != null) {
            return onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layout.panelScrollDownX = event.x
                layout.panelScrollDownY = event.y
                layout.panelScrollLastY = event.y
                layout.panelScrollActive = false
                layout.panelScrollTouchTarget = layout.findTouchTarget(event.x, event.y)
                layout.editTapDownX = event.x
                layout.editTapDownY = event.y
                val child = layout.panelScrollTouchTarget
                if (child != null) {
                    val localX = event.x - child.left - child.translationX
                    val localY = event.y - child.top - child.translationY
                    if (!child.isTouchOnChrome(localX, localY)) {
                        scheduleWidgetDragLongPress(child, event.x, event.y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (maybeStartPanelScroll(event)) {
                    return dispatchPanelNestedScroll(event)
                }
                if (layout.pendingDragChild != null) {
                    val dx = event.x - layout.pendingDragDownX
                    val dy = event.y - layout.pendingDragDownY
                    if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                        cancelPendingDrag()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 编辑模式点按「应用 / 动作 / 快捷方式」格子（没拖动也没滚动）
                // → 打开该格子的二级菜单编辑器；小组件卡不响应。
                // ⚠️ 判定必须用「按下点与抬起点的距离」：DOWN 时一定会先登记
                // 「拖拽待命」（长按后进入拖动），到 UP 时它还没被取消——
                // 若用「待命是否存在」判断，任何一次正常点击都会被误判成拖拽。
                val tapTarget = layout.panelScrollTouchTarget
                val tapDx = event.x - layout.editTapDownX
                val tapDy = event.y - layout.editTapDownY
                val isTap = tapDx * tapDx + tapDy * tapDy <= layout.touchSlop * layout.touchSlop
                val scrolled = layout.panelScrollActive
                cancelPendingDrag()
                layout.panelScrollTouchTarget = null
                stopPanelScroll()
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    !scrolled &&
                    isTap &&
                    tapTarget != null &&
                    tapTarget.item.itemType != ITEM_TYPE_WIDGET
                ) {
                    layout.onEditSubActions?.invoke(tapTarget.item)
                }
                return true
            }
        }
        return true
    }

    private fun scheduleWidgetDragLongPress(child: WidgetCardContainer, x: Float, y: Float) {
        cancelPendingDrag()
        layout.pendingDragChild = child
        layout.pendingDragDownX = x
        layout.pendingDragDownY = y
        layout.pendingDragLongPress = Runnable {
            val target = layout.pendingDragChild
            if (target !== child || layout.draggingChild != null || !layout.editMode) {
                cancelPendingDrag()
                return@Runnable
            }
            layout.pendingDragChild = null
            layout.pendingDragLongPress = null
            startDrag(child, x, y)
        }
        layout.postDelayed(layout.pendingDragLongPress!!, layout.longPressTimeout)
    }

    private fun scheduleBlankLongPress() {
        cancelPendingLongPress()
        layout.pendingLongPress = Runnable {
            if (!layout.blankTouchTracking || layout.editMode) return@Runnable
            if (layout.findTouchTarget(layout.blankTouchDownX, layout.blankTouchDownY) == null) {
                layout.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                layout.onLongPressBlank?.invoke()
            }
            layout.blankTouchTracking = false
        }
        layout.postDelayed(layout.pendingLongPress!!, layout.longPressTimeout)
    }

    private fun handleBlankAreaTouch(event: MotionEvent): Boolean {
        if (layout.editMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (layout.findTouchTarget(event.x, event.y) != null) return false
                layout.blankTouchTracking = true
                layout.blankTouchDownX = event.x
                layout.blankTouchDownY = event.y
                scheduleBlankLongPress()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!layout.blankTouchTracking) return false
                val dx = event.x - layout.blankTouchDownX
                val dy = event.y - layout.blankTouchDownY
                if (dx * dx + dy * dy > layout.touchSlop * layout.touchSlop) {
                    cancelPendingLongPress()
                    layout.blankTouchTracking = false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!layout.blankTouchTracking) return false
                cancelPendingLongPress()
                val tappedBlank = event.actionMasked == MotionEvent.ACTION_UP &&
                    layout.findTouchTarget(event.x, event.y) == null
                layout.blankTouchTracking = false
                if (tappedBlank) {
                    layout.onTapBlank?.invoke()
                }
                return true
            }
        }
        return false
    }

    private fun startDrag(child: WidgetCardContainer, x: Float, y: Float) {
        layout.dragStartPage = layout.canvasPage
        child.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        layout.draggingChild = child
        layout.draggingItem = child.item
        layout.dragTouchOffsetX = x - (child.left + child.translationX)
        layout.dragTouchOffsetY = y - (child.top + child.translationY)
        child.bringToFront()
        child.translationZ = 8f * layout.resources.displayMetrics.density
        child.animate().cancel()
        child.scaleX = 1.05f
        child.scaleY = 1.05f
        child.alpha = 0.88f
        layout.updateInteractionActive(true)
        layout.requestDisallowInterceptAllParents(true)
        WidgetCanvasLayoutGeometry.updateHoverCell(layout, x, y)
        layout.applyDragLiveReorderPreview()
        layout.invalidate()
    }

    private fun endDrag(child: WidgetCardContainer) {
        val initialPage = layout.dragStartPage ?: layout.canvasPage
        val item = layout.draggingItem ?: return
        child.animate().cancel()
        child.scaleX = 1f
        child.scaleY = 1f
        child.alpha = 1f
        child.translationZ = 0f
        layout.updateInteractionActive(false)
        layout.requestDisallowInterceptAllParents(false)
        layout.resetAllChildrenLiveTranslations()

        if (initialPage != null && (layout.hoverCellX != item.x || layout.hoverCellY != item.y) && layout.hoverCellX >= 0 && layout.hoverCellY >= 0) {
            child.translationX = 0f
            child.translationY = 0f
            val updatedPage = WidgetPanelGridLogic.moveItemWithAutoSwapOrShift(initialPage, item, layout.hoverCellX, layout.hoverCellY)
            if (updatedPage != initialPage) {
                layout.commitPageChange(updatedPage)
            } else {
                layout.requestLayout()
            }
        } else {
            child.translationX = 0f
            child.translationY = 0f
            layout.requestLayout()
        }
        layout.dragStartPage = null
        layout.draggingChild = null
        layout.draggingItem = null
        layout.hoverCellX = -1
        layout.hoverCellY = -1
        layout.invalidate()
    }

    private fun shouldDelegateWidgetBodyTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val child = layout.findTouchTarget(event.x, event.y) ?: return false
                val localX = event.x - child.left - child.translationX
                val localY = event.y - child.top - child.translationY
                if (child.isTouchOnChrome(localX, localY)) return false
                if (!child.shouldReceiveTouchAtPoint(localX, localY)) return false
                layout.panelScrollTouchTarget = child
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val delegated = layout.panelScrollTouchTarget != null
                layout.panelScrollTouchTarget = null
                return delegated
            }
            else -> return layout.panelScrollTouchTarget != null
        }
    }

    private fun shouldDeferPanelScrollToWidget(dy: Float): Boolean {
        val target = layout.panelScrollTouchTarget ?: return false
        val localX = layout.panelScrollDownX - target.left - target.translationX
        val localY = layout.panelScrollDownY - target.top - target.translationY
        if (target.isTouchOnChrome(localX, localY)) return false
        // 只要小组件里这一方向还能滚，就一直让位；不再用「拖过 N 倍 slop 就收回」的
        // 距离窗口（那会导致滑两下就变成面板滚动）。
        return target.canContentScrollAtPoint(localX, localY, 0, dy)
    }
}
