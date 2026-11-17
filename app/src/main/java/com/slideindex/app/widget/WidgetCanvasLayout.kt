package com.slideindex.app.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.min
import kotlin.math.roundToInt

class WidgetCanvasLayout(context: Context) : ViewGroup(context) {

  var editMode: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      touchHandler.cancelPendingLongPress()
      touchHandler.cancelPendingDrag()
      touchHandler.cancelBrowseLongPress()
      if (!value) {
        touchHandler.resetTouchInteractionState()
      }
      setWillNotDraw(!value)
      refreshEditChrome()
      syncDynamicRowCount()
      invalidate()
    }

  var onLongPressBlank: (() -> Unit)? = null
  var onTapBlank: (() -> Unit)? = null
  var onItemChanged: ((WidgetPanelItem) -> Unit)? = null
  var onPageCommitted: ((WidgetPanelPage) -> Unit)? = null
  var onItemRemoved: ((Int) -> Unit)? = null
  var onConfigureWidget: ((Int) -> Unit)? = null
  var onAddWidgetRequested: (() -> Unit)? = null
  var onInteractionActiveChange: ((Boolean) -> Unit)? = null
  /** 编辑模式点按「应用 / 动作 / 快捷方式」格子 → 打开该格子的二级菜单编辑器。 */
  var onEditSubActions: ((WidgetPanelItem) -> Unit)? = null

  var pageColumnCount: Int = 4
  var pageRowCount: Int = 26
  var currentGridStepPx: Int = 0
    internal set
  val gridStepPx: Int get() = currentGridStepPx

  /**
   * 应用/快捷方式/动作格子是否显示名称。
   *
   * 卡片是正方形：44dp 图标 + 4dp 间距 + 一行 11sp 名称要 63~64dp，格子更小时名称会被
   * 下一行图标裁掉。关闭后只画图标（名称仍由 [WidgetCardContainer] 提供给无障碍朗读）。
   */
  var appLabelsVisible: Boolean = true
    set(value) {
      if (field == value) return
      field = value
      for (i in 0 until childCount) {
        (getChildAt(i) as? WidgetCardContainer)?.setAppLabelsVisible(value)
      }
    }

  internal var canvasPage: WidgetPanelPage? = null
  internal var dragStartPage: WidgetPanelPage? = null
  internal var canvasHostContext: Context? = null
  val currentPage: WidgetPanelPage? get() = canvasPage

  private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x55FFFFFF
    style = Paint.Style.FILL
  }
  private val gridDotEditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x99FFFFFF.toInt()
    style = Paint.Style.FILL
  }
  private val dragPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x664CAF50
    style = Paint.Style.FILL
  }
  private val resizePreviewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x33FFFFFF
    style = Paint.Style.FILL
  }
  private val resizePreviewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xCCFFFFFF.toInt()
    style = Paint.Style.STROKE
    strokeWidth = 2f * resources.displayMetrics.density
    pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
  }
  private val previewRect = RectF()
  private val dragPreviewRect = RectF()

  internal var draggingChild: WidgetCardContainer? = null
  internal var draggingItem: WidgetPanelItem? = null
  internal var dragTouchOffsetX = 0f
  internal var dragTouchOffsetY = 0f
  internal var hoverCellX = -1
  internal var hoverCellY = -1
  internal var canvasInteractionActive = false
  internal var chromeTouchTarget: WidgetCardContainer? = null

  // ── 长按「二级菜单」────────────────────────────────────────────
  // 规格：长按应用 / 动作 / 快捷方式格子 → 在该格子周围弹一圈动作；
  // 不移动直接松手 = 以**小窗**打开该格子；移到某一项松手 = 执行那一项的动作。

  /** 二级菜单状态；null = 未弹出。 */
  /**
   * 分帧建卡的参数。
   *
   * 用**时间预算**而不是固定张数：卡片成本差别很大（小组件要建宿主视图、
   * 应用格子只建视图），固定 6 张时遇到重卡会把一帧拖到 100ms+，
   * 动画放完了内容还没铺满（"空位补上"）。按 8ms/帧 的预算尽量多建，
   * 单张超过预算就下一帧再继续。
   */
  private val BIND_FRAME_BUDGET_MS = 8L
  private val CARD_FADE_IN_MS = 140L

  /** 分帧建卡的代际号：收起 / 重新 bind 时自增，让旧的递归立刻停下。 */
  private var bindChunkToken = 0



  internal var subMenu: SubMenuState? = null

  /** 二级菜单里一项的绘制 / 命中信息（图标提前解析好，绘制时不再查表）。 */
  internal class SubMenuState(
    val anchorX: Float,
    val anchorY: Float,
    val items: List<WidgetPanelSubAction>,
    val icons: List<android.graphics.Bitmap?>,
    val itemRadiusPx: Float,
    /** 网格步长（项直径 + 间距）。 */
    val stepPx: Float,
    val columns: Int,
    val rows: Int,
    val offsetXPx: Float,
    val offsetYPx: Float,
    val showLabels: Boolean,
    /** 图标绘制半径（按 iconSizePercent 折算过）。 */
    val iconRadiusPx: Float,
    /** 名称文字大小（px，按 labelSizePercent 折算过）。 */
    val labelTextSizePx: Float,
    /** 容器背景 alpha（0..255，按 backgroundAlphaPercent 折算过）。 */
    val backgroundAlpha: Int,
    /** 容器圆角（px）。 */
    val cornerRadiusPx: Float,
    /** 菜单级默认容器色；每格还有自己的覆盖色。 */
    val defaultContainerColor: Int,
    /** 菜单级默认文字 / 图标色；每格还有自己的覆盖色。 */
    val defaultLabelColor: Int,
    /** 每格的容器色（0 = 跟随默认）。 */
    val containerColors: List<Int>,
    /** 每格的文字 / 图标色（0 = 跟随默认）。 */
    val iconTints: List<Int>,
  ) {
    var hovered: Int = -1
    /**
     * 长按结算标记：手指在某一项上停满长按时间后，该动作**已按小窗执行**，
     * 松手时不能再执行一次（否则小窗 + 普通启动双开）。
     */
    var longPressConsumed: Boolean = false

    /**
     * 菜单弹出后手指是否移动过（超过 touchSlop）。
     *
     * 用来区分两种「松手在空白处」：
     * - 没移动 → 视为"只是长按了这个格子"，按原方式打开（应用 = 小窗）；
     * - 移动过 → 用户是在找菜单项没找到，**只收菜单，什么都不执行**。
     */
    var moved: Boolean = false

    /** 实际占用的行数（项目可能少于配置行数）。 */
    val usedRows: Int get() = ((items.size + columns - 1) / columns).coerceAtLeast(1)
  }

  /** 「在某一项上长按」的计时器：停满长按时间 → 该项以小窗打开。 */
  internal var subMenuLongPressRunnable: Runnable? = null
  internal var subMenuLongPressIndex: Int = -1

  /** 本次手势里「长按某一项」已经结算过：松手时不要再执行一次。 */
  internal var subMenuLongPressFired: Boolean = false

  // ── 没配二级菜单的格子：长按 = 直接按"小窗"打开 ──────────────────
  // 需求：长按弹菜单是"配置过菜单"的格子才有的；没配过的格子长按照旧行为
  // 直接以小窗打开（应用）/ 按原方式执行（动作、快捷方式），不能什么都不发生。

  /** 已触发长按、等待松手结算的格子（null = 没有）。 */
  internal var noMenuArmedChild: WidgetCardContainer? = null
  internal var noMenuTouchDownX = 0f
  internal var noMenuTouchDownY = 0f
  internal var noMenuTracking = false
  internal var noMenuPendingRunnable: Runnable? = null

  /**
   * 第 [index] 项的中心（绘制与命中都用它，保证「画哪儿点哪儿」）。
   *
   * 布局 = **行列网格**（按配置的行列数、可整体偏移），不再是一圈环绕：
   * 项目按行优先排列，整块以格子中心为基准居中；配置行数大于实际行数时，
   * 按配置行数的高度居中，这样"少放几个也不会跳位置"。
   */
  internal fun subMenuItemCenter(state: SubMenuState, index: Int): Pair<Float, Float> {
    val row = index / state.columns
    val column = index % state.columns
    val layoutRows = state.rows.coerceAtLeast(state.usedRows)
    val gridWidth = state.columns * state.stepPx
    val gridHeight = layoutRows * state.stepPx
    val left = state.anchorX - gridWidth / 2f + state.offsetXPx
    val top = state.anchorY - gridHeight / 2f + state.offsetYPx
    val cx = left + state.stepPx / 2f + column * state.stepPx
    val cy = top + state.stepPx / 2f + row * state.stepPx
    return cx to cy
  }

  /** 命中测试：返回菜单项下标，-1 = 不在任何一项上（= 视为「没移动」）。 */
  internal fun subMenuHitAt(x: Float, y: Float): Int {
    val state = subMenu ?: return -1
    var best = -1
    var bestDistance = Float.MAX_VALUE
    for (index in state.items.indices) {
      val (cx, cy) = subMenuItemCenter(state, index)
      val dx = x - cx
      val dy = y - cy
      val distance = kotlin.math.hypot(dx, dy)
      // 命中半径略大于绘制半径：手指边缘压到也算选中，少一点"松开没反应"。
      if (distance <= state.itemRadiusPx * 1.4f && distance < bestDistance) {
        best = index
        bestDistance = distance
      }
    }
    return best
  }

  /** 弹出 / 收起二级菜单。 */
  internal fun showSubMenu(child: WidgetCardContainer) {
    val actions = WidgetPanelSubActions.parse(child.item.subActions)
    if (actions.isEmpty()) {
      subMenu = null
      invalidate()
      return
    }
    val config = child.item.subMenu.normalized()
    val dm = resources.displayMetrics
    // 容器尺寸 / 圆角 / 间距 / 图标占比 / 文字大小 / 背景不透明度全部来自配置，
    // 口径与快捷轮盘的容器外观参数一致。
    val itemDiameter = config.containerSizeDp * dm.density
    val itemRadius = itemDiameter / 2f
    val iconPx = (itemDiameter * config.iconSizePercent / 100f).toInt().coerceAtLeast(1)
    val anchorX = child.left + child.translationX + child.width / 2f
    val anchorY = child.top + child.translationY + child.height / 2f
    // 每格颜色：0 = 跟随菜单级设置。
    val containerColors = actions.map { it.containerColorArgb.takeIf { c -> c != 0L }?.toInt() ?: 0 }
    val iconTints = actions.map { it.iconTintArgb.takeIf { c -> c != 0L }?.toInt() ?: 0 }
    subMenu = SubMenuState(
      anchorX = anchorX,
      anchorY = anchorY,
      items = actions,
      icons = actions.mapIndexed { index, item ->
        // 文字 / 图标色跟着「该格自己的覆盖色 → 菜单级默认」走，这样图标和文字颜色一致。
        val tint = iconTints[index].takeIf { it != 0 } ?: config.labelColorArgb.toInt()
        WidgetPanelSubActions.icon(context, item, iconPx, tint)
      },
      itemRadiusPx = itemRadius,
      stepPx = itemDiameter + config.itemGapDp * dm.density,
      columns = config.columns,
      rows = config.rows,
      offsetXPx = config.offsetXDp * dm.density,
      offsetYPx = config.offsetYDp * dm.density,
      showLabels = config.showLabels,
      iconRadiusPx = iconPx / 2f,
      labelTextSizePx = 11f * config.labelSizePercent / 100f * dm.density,
      backgroundAlpha = (255 * config.backgroundAlphaPercent / 100f).toInt().coerceIn(0, 255),
      cornerRadiusPx = config.containerCornerDp * dm.density,
      defaultContainerColor = config.containerColorArgb.toInt(),
      defaultLabelColor = config.labelColorArgb.toInt(),
      containerColors = containerColors,
      iconTints = iconTints,
    )
    // 菜单开着时禁止**所有父容器**拦截手势：面板外侧是「左右滑动切页」的 ViewPager、
    // 内侧是竖向滚动的 RecyclerView，不禁止的话手指一划就被它们抢走
    // （真机反馈「左右滑切成切页、上下滑菜单消失」）。
    requestDisallowInterceptAllParents(true)
    invalidate()
  }

  internal fun dismissSubMenu() {
    if (subMenu == null) return
    cancelSubMenuLongPress()
    subMenu = null
    // 菜单收起 → 把手势还给父容器（切页 / 滚动）。
    requestDisallowInterceptAllParents(false)
    invalidate()
  }

  /** 取消「指着某一项长按 = 小窗」的计时。 */
  internal fun cancelSubMenuLongPress() {
    subMenuLongPressRunnable?.let { removeCallbacks(it) }
    subMenuLongPressRunnable = null
    subMenuLongPressIndex = -1
  }

  private val subMenuScrimPaint = Paint().apply { color = 0x88000000.toInt() }
  private val subMenuItemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xF2FFFFFF.toInt()
    style = Paint.Style.FILL
  }
  private val subMenuItemHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF3478F6.toInt()
    style = Paint.Style.FILL
  }

  /** 长按"上膛"（松手即以小窗打开）时的底色：与普通选中区分开，用户能预期结果。 */
  private val subMenuItemArmedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFF2E9E5B.toInt()
    style = Paint.Style.FILL
  }
  private val subMenuLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0xFFFFFFFF.toInt()
    textAlign = Paint.Align.CENTER
  }

  private fun drawSubMenu(canvas: Canvas) {
    val state = subMenu ?: return
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), subMenuScrimPaint)
    val textSize = state.labelTextSizePx
    subMenuLabelPaint.textSize = textSize
    for (index in state.items.indices) {
      val (cx, cy) = subMenuItemCenter(state, index)
      val hovered = state.hovered == index
      val armed = hovered && state.longPressConsumed
      val tint = state.iconTints.getOrNull(index).takeIf { it != null && it != 0 }
        ?: state.defaultLabelColor
      // 容器色：每格自己的 → 菜单级默认；选中 / 上膛时换成对应强调色但保留 alpha。
      val baseColor = state.containerColors.getOrNull(index).takeIf { it != null && it != 0 }
        ?: state.defaultContainerColor
      val bgColor = when {
        armed -> 0xFF2E9E5B.toInt()
        hovered -> 0xFF3478F6.toInt()
        else -> baseColor
      }
      subMenuItemPaint.color = bgColor
      subMenuItemPaint.alpha = state.backgroundAlpha
      val r = state.itemRadiusPx
      val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
      canvas.drawRoundRect(rect, state.cornerRadiusPx, state.cornerRadiusPx, subMenuItemPaint)
      state.icons[index]?.let { icon ->
        val half = state.iconRadiusPx
        val dst = android.graphics.RectF(cx - half, cy - half, cx + half, cy + half)
        canvas.drawBitmap(icon, null, dst, null)
      }
      // 名称：开了「显示名称」就每项都画（与轮盘一致）；否则只在指着的那项画，
      // 避免一排小圆点猜不出是什么。
      if (state.showLabels || hovered) {
        val label = WidgetPanelSubActions.displayName(context, state.items[index])
        if (label.isNotBlank()) {
          subMenuLabelPaint.color = tint
          canvas.drawText(label, cx, cy + state.itemRadiusPx + textSize * 1.15f, subMenuLabelPaint)
        }
      }
    }
  }


  internal val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  internal val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
  internal var blankTouchTracking = false
  internal var blankTouchDownX = 0f
  internal var blankTouchDownY = 0f
  internal var pendingLongPress: Runnable? = null

  // 长按二级菜单的待触发状态（与「长按空白进编辑」那套互不干扰）。
  internal var pendingSubMenuLongPress: Runnable? = null
  internal var subMenuTouchChild: WidgetCardContainer? = null
  internal var subMenuTouchDownX = 0f
  internal var subMenuTouchDownY = 0f
  internal var subMenuTouchTracking = false

  internal var pendingDragChild: WidgetCardContainer? = null
  internal var pendingDragDownX = 0f
  internal var pendingDragDownY = 0f
  internal var pendingDragLongPress: Runnable? = null
  // 编辑模式「点按」判定：按下点与抬起点的距离 ≤ touchSlop 才算点按（不是拖拽/滚动）。
  internal var editTapDownX = 0f
  internal var editTapDownY = 0f
  internal var browseTouchChild: WidgetCardContainer? = null
  internal var browseTouchDownX = 0f
  internal var browseTouchDownY = 0f
  internal var browseTouchTracking = false
  internal var browseLongPressConsumed = false
  internal var pendingBrowseLongPress: Runnable? = null

  internal val nestedScrollChildHelper = NestedScrollingChildHelper(this)
  internal var panelScrollLastY = 0f
  internal var panelScrollDownX = 0f
  internal var panelScrollDownY = 0f
  internal var panelScrollActive = false
  internal var panelScrollTouchTarget: WidgetCardContainer? = null

  internal var lastBindKey: BindKey? = null
  private var lastGridStepPx = 0

  private val touchHandler = WidgetCanvasTouchHandler(this)

  init {
    clipChildren = false
    clipToPadding = false
    setWillNotDraw(true)
    isNestedScrollingEnabled = true
    nestedScrollChildHelper.isNestedScrollingEnabled = true
  }

  internal fun superDispatchTouchEvent(event: MotionEvent): Boolean =
    super.dispatchTouchEvent(event)

  fun syncDynamicRowCount() {
    val page = canvasPage ?: return
    val targetRows = if (editMode) {
      WidgetPanelGridLogic.computeEditRowCount(page, EDIT_MODE_BUFFER_ROWS)
    } else {
      WidgetPanelGridLogic.computeContentRowCount(page)
    }
    if (pageRowCount != targetRows) {
      pageRowCount = targetRows
      requestLayout()
      invalidate()
    }
  }

  fun ensureBufferRowsBelow(requiredMaxY: Int) {
    if (!editMode) return
    val minNeededRows = requiredMaxY + EDIT_MODE_BUFFER_ROWS
    if (minNeededRows > pageRowCount) {
      pageRowCount = minNeededRows
      requestLayout()
      invalidate()
    }
  }

  fun bind(page: WidgetPanelPage, hostContext: Context) {
    WidgetPanelProbe.canvasRebind(page.id, page.items.size)
    canvasHostContext = hostContext
    canvasPage = page
    pageColumnCount = page.columnCount
    syncDynamicRowCount()
    lastBindKey = WidgetCanvasLayoutGeometry.bindKeyFor(page)

    // ⚠️ 卡片**分帧**建，不再一帧内全建完：
    // 呼出时要先让窗口 + 入场动画跑起来，把所有卡片一次性建完会把这一帧卡死
    // （小组件/图标越多越久），看起来就是"呼出顿一下"。
    // 现在每帧只建 CHUNK 张，卡片自己淡入，动画期间逐步铺满 —— 观感是"弹出来"。
    removeAllViews()
    val items = page.items
    if (items.isEmpty()) {
      postRefreshAllWidgetLayouts()
      requestLayout()
      invalidate()
      return
    }
    bindChunkToken = ++bindGeneration
    val token = bindChunkToken
    fun buildChunk(start: Int) {
      if (token != bindChunkToken || token != bindGeneration) return
      val frameStartNs = System.nanoTime()
      var index = start
      while (index < items.size) {
        val card = addWidgetCard(hostContext, items[index])
        card.alpha = 0f
        card.animate().alpha(1f).setDuration(CARD_FADE_IN_MS).start()
        index++
        // 至少建一张；之后按 8ms 预算，超了就把剩下的丢给下一帧。
        val elapsedMs = (System.nanoTime() - frameStartNs) / 1_000_000
        if (elapsedMs >= BIND_FRAME_BUDGET_MS) break
      }
      if (index < items.size) {
        post { buildChunk(index) }
      } else {
        postRefreshAllWidgetLayouts()
        requestLayout()
        invalidate()
      }
    }
    requestLayout()
    invalidate()
    post { buildChunk(0) }
  }

  /** 取消尚未执行的分帧建卡（收起 / 重新 bind 时调用）。 */
  internal fun cancelPendingBind() {
    bindChunkToken++
  }

  fun bindIfNeeded(page: WidgetPanelPage, hostContext: Context) {
    WidgetCanvasLayoutGeometry.bindIfNeeded(this, page, hostContext)
  }

  fun removeWidgetCard(appWidgetId: Int) {
    val page = canvasPage ?: return
    if (page.items.none { it.appWidgetId == appWidgetId }) return
    canvasPage = WidgetPanelGridLogic.removeItem(page, appWidgetId)
    for (i in childCount - 1 downTo 0) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (child.item.appWidgetId == appWidgetId) {
        removeViewAt(i)
        break
      }
    }
    canvasPage?.let { lastBindKey = WidgetCanvasLayoutGeometry.bindKeyFor(it) }
    syncDynamicRowCount()
    requestLayout()
    invalidate()
  }

  fun addWidgetCard(hostContext: Context, item: WidgetPanelItem): WidgetCardContainer {
    // 小组件宿主视图在**建卡时同步取**（与上游一致）：binder 量很小，
    // 同步做的好处是窗口首帧就是完整面板，不会出现"小组件位置先空白一下"。
    // 真正影响呼出速度的是图标解析，那部分已经全部挪到后台线程。
    val hostView = if (item.itemType == ITEM_TYPE_WIDGET) {
      WidgetPopupHost.obtainHostView(hostContext, item.appWidgetId)
    } else {
      null
    }
    val container = WidgetCardContainer(context, item, hostView).apply {
      onDelete = {
        val widgetId = this.item.appWidgetId
        removeWidgetCard(widgetId)
        onItemRemoved?.invoke(widgetId)
      }
      onConfigure = { onConfigureWidget?.invoke(item.appWidgetId) }
      // 编辑模式下「应用 / 动作 / 快捷方式」格子左上角的「⋯」按钮 → 二级菜单配置。
      // 走 chrome 按钮这条成熟通道（与小部件卡的「配置」按钮同一套命中逻辑），
      // 不再依赖画布的点击判定——那条路在 RecyclerView + 卡片点击视图下不可靠。
      val canvas = this@WidgetCanvasLayout
      onEditSubActions = {
        val target = this
        if (target.item.itemType != ITEM_TYPE_WIDGET) {
          canvas.onEditSubActions?.invoke(target.item)
        }
      }
      onResize = { spanX, spanY ->
        commitItemChange(this.item.copy(spanX = spanX, spanY = spanY))
      }
      onInteractionActiveChange = { active -> updateInteractionActive(active) }
      setEditMode(editMode)
      layoutParams = LayoutParams(0, 0)
    }
    container.setAppLabelsVisible(appLabelsVisible)
    addView(container)
    container.post { container.updateScalableTargetFromContainer() }
    return container
  }

  fun refreshAllWidgetLayouts() {
    for (i in 0 until childCount) {
      (getChildAt(i) as? WidgetCardContainer)?.refreshWidgetLayout(force = true)
    }
  }

  fun notifyResizePreviewChanged() {
    invalidate()
  }

  fun commitItemChange(item: WidgetPanelItem) {
    val page = canvasPage ?: return
    if (!WidgetPanelGridLogic.isAreaFree(
        page,
        item.x,
        item.y,
        item.spanX,
        item.spanY,
        item.appWidgetId,
      )
    ) {
      requestLayout()
      return
    }
    val updatedPage = WidgetPanelGridLogic.upsertItem(page, item)
    canvasPage = updatedPage
    lastBindKey = WidgetCanvasLayoutGeometry.bindKeyFor(updatedPage)
    findChildByWidgetId(item.appWidgetId)?.let { child ->
      child.syncItem(item)
      child.refreshWidgetLayout(force = true)
    }
    syncDynamicRowCount()
    requestLayout()
    onPageCommitted?.invoke(updatedPage)
    onItemChanged?.invoke(item)
  }

  fun commitPageChange(updatedPage: WidgetPanelPage) {
    canvasPage = updatedPage
    lastBindKey = WidgetCanvasLayoutGeometry.bindKeyFor(updatedPage)
    WidgetCanvasLayoutGeometry.syncItemsFromPage(this, updatedPage)
    syncDynamicRowCount()
    requestLayout()
    invalidate()
    onPageCommitted?.invoke(updatedPage)
  }

  fun applyDragLiveReorderPreview() {
    val initialPage = dragStartPage ?: return
    val dragging = draggingChild ?: return
    val item = draggingItem ?: return
    val hoverX = hoverCellX
    val hoverY = hoverCellY
    if (hoverX < 0 || hoverY < 0) return

    val previewPage = WidgetPanelGridLogic.moveItemWithAutoSwapOrShift(initialPage, item, hoverX, hoverY)
    val step = currentGridStepPx
    if (step <= 0) return

    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (child == dragging) continue
      val targetItem = previewPage.items.find { it.appWidgetId == child.item.appWidgetId } ?: child.item
      val targetLeft = paddingLeft + targetItem.x * step
      val targetTop = paddingTop + targetItem.y * step
      val targetTransX = (targetLeft - child.left).toFloat()
      val targetTransY = (targetTop - child.top).toFloat()

      child.animate()
        .translationX(targetTransX)
        .translationY(targetTransY)
        .setDuration(160)
        .setInterpolator(android.view.animation.DecelerateInterpolator())
        .start()
    }
  }

  fun resetAllChildrenLiveTranslations() {
    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (child != draggingChild) {
        child.animate().cancel()
        child.translationX = 0f
        child.translationY = 0f
      }
    }
  }

  internal fun findChildByWidgetId(appWidgetId: Int): WidgetCardContainer? {
    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (child.item.appWidgetId == appWidgetId) return child
    }
    return null
  }

  internal fun findTouchTarget(x: Float, y: Float): WidgetCardContainer? {
    for (i in childCount - 1 downTo 0) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      val localX = x - child.left - child.translationX
      val localY = y - child.top - child.translationY
      if (child.isTouchOnChrome(localX, localY)) return child
      val left = child.left + child.translationX
      val top = child.top + child.translationY
      if (x >= left && x <= left + child.width && y >= top && y <= top + child.height) {
        return child
      }
    }
    return null
  }

  internal fun deliverTouchToChild(child: WidgetCardContainer, event: MotionEvent): Boolean {
    val transformed = MotionEvent.obtain(event)
    transformed.offsetLocation(
      -child.left - child.translationX,
      -child.top - child.translationY,
    )
    val handled = child.dispatchTouchEvent(transformed)
    transformed.recycle()
    return handled
  }

  internal fun updateInteractionActive(active: Boolean) {
    if (canvasInteractionActive == active) return
    canvasInteractionActive = active
    onInteractionActiveChange?.invoke(active)
    if (!active && !anyChildPreviewingResize()) {
      postRefreshAllWidgetLayouts()
    }
  }

  /**
   * 排队刷新所有卡片布局。
   *
   * 排出去的 runnable 可能在页面被 RecyclerView 回收/重绑之后才执行；那时卡片都已 detach，
   * 刷新会落到 detached 的宿主视图上（`View.getHandler()` 为 null → NPE）。
   * 真机现象：滑到第 2 页长按（进编辑态会重绑所有页）闪退。
   */
  private fun postRefreshAllWidgetLayouts() {
    post {
      if (!isAttachedToWindow) return@post
      refreshAllWidgetLayouts()
    }
  }

  internal fun anyChildPreviewingResize(): Boolean {
    for (i in 0 until childCount) {
      if ((getChildAt(i) as? WidgetCardContainer)?.isPreviewingResize() == true) return true
    }
    return false
  }

  internal fun requestDisallowInterceptAllParents(disallow: Boolean) {
    var current: android.view.ViewParent? = parent
    while (current != null) {
      current.requestDisallowInterceptTouchEvent(disallow)
      current = current.parent
    }
  }

  private fun refreshEditChrome() {
    for (i in 0 until childCount) {
      (getChildAt(i) as? WidgetCardContainer)?.setEditMode(editMode)
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val widthMode = MeasureSpec.getMode(widthMeasureSpec)
    val widthSize = MeasureSpec.getSize(widthMeasureSpec)
    val heightMode = MeasureSpec.getMode(heightMeasureSpec)
    val heightSize = MeasureSpec.getSize(heightMeasureSpec)

    val innerWidthAvailable = if (widthMode != MeasureSpec.UNSPECIFIED && widthSize > 0) {
      widthSize - paddingLeft - paddingRight
    } else {
      0
    }

    val desiredCellPx = if ((canvasPage?.cellWidthDp ?: 0) > 0) {
      (canvasPage!!.cellWidthDp * resources.displayMetrics.density).roundToInt()
    } else 0

    // 固定单元宽度只在放不下时按可用宽度收缩，避免最右一列被容器裁掉。
    currentGridStepPx = WidgetGridMetrics.resolveGridStepPx(
      innerWidthPx = innerWidthAvailable,
      columnCount = pageColumnCount,
      desiredCellPx = desiredCellPx,
    )

    val contentW = pageColumnCount * currentGridStepPx
    val contentH = pageRowCount * currentGridStepPx
    val desiredW = paddingLeft + paddingRight + contentW
    val desiredH = paddingTop + paddingBottom + contentH

    val measuredW = when (widthMode) {
      MeasureSpec.EXACTLY -> widthSize
      MeasureSpec.AT_MOST -> min(widthSize, desiredW)
      else -> desiredW
    }
    val measuredH = when (heightMode) {
      MeasureSpec.EXACTLY -> heightSize
      MeasureSpec.AT_MOST -> min(heightSize, desiredH)
      else -> desiredH
    }
    setMeasuredDimension(measuredW, measuredH)

    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      val childW = child.previewLayoutWidthPx(currentGridStepPx)
      val childH = child.previewLayoutHeightPx(currentGridStepPx)
      child.measure(
        MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY),
      )
    }
  }

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (child == draggingChild) continue
      val item = child.item
      val left = paddingLeft + item.x * currentGridStepPx
      val top = paddingTop + item.y * currentGridStepPx
      child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
    }
    if (currentGridStepPx != lastGridStepPx) {
      lastGridStepPx = currentGridStepPx
      if (!canvasInteractionActive && !browseTouchTracking) {
        postRefreshAllWidgetLayouts()
      }
    }
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean =
    touchHandler.dispatchTouchEvent(event)

  override fun onDetachedFromWindow() {
    // 页被重建/摘除时，长按计时器还挂在主线程上：不撤销的话，它会把重建后的新画布直接带进编辑态。
    touchHandler.cancelPendingTimers()
    super.onDetachedFromWindow()
  }

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
    touchHandler.onInterceptTouchEvent(event)

  @SuppressLint("ClickableViewAccessibility") // Canvas drag/resize surface; not a clickable control
  override fun onTouchEvent(event: MotionEvent): Boolean =
    touchHandler.onTouchEvent(event)

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    // 二级菜单画在所有格子**之上**（super.dispatchDraw 已经画完子视图）。
    drawSubMenu(canvas)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (currentGridStepPx <= 0) return
    val step = currentGridStepPx.toFloat()
    val radiusPx = (if (editMode) 2.5f else 2f) * resources.displayMetrics.density
    val dotPaint = if (editMode) gridDotEditPaint else gridDotPaint
    val cornerRadius = 16f * resources.displayMetrics.density
    val cols = pageColumnCount
    val rows = pageRowCount

    for (y in 0..rows) {
      for (x in 0..cols) {
        val cx = paddingLeft + x * step
        val cy = paddingTop + y * step
        canvas.drawCircle(cx, cy, radiusPx, dotPaint)
      }
    }

    if (!editMode) return

    for (i in 0 until childCount) {
      val child = getChildAt(i) as? WidgetCardContainer ?: continue
      if (!child.isPreviewingResize()) continue
      val item = child.item
      val spanX = child.layoutSpanX()
      val spanY = child.layoutSpanY()
      val page = canvasPage
      val isFree = page == null ||
        WidgetPanelGridLogic.isAreaFree(page, item.x, item.y, spanX, spanY, item.appWidgetId)
      val left = paddingLeft + item.x * step
      val top = paddingTop + item.y * step
      val right = left + spanX * step
      val bottom = top + spanY * step
      previewRect.set(left, top, right, bottom)
      resizePreviewFillPaint.color = if (isFree) 0x33FFFFFF else 0x33F44336
      resizePreviewStrokePaint.color = if (isFree) 0xCCFFFFFF.toInt() else 0xCCF44336.toInt()
      canvas.drawRoundRect(previewRect, cornerRadius, cornerRadius, resizePreviewFillPaint)
      canvas.drawRoundRect(previewRect, cornerRadius, cornerRadius, resizePreviewStrokePaint)
    }

    if (draggingChild != null && hoverCellX >= 0 && hoverCellY >= 0) {
      val item = draggingItem!!
      val page = canvasPage ?: return
      val isFree = WidgetPanelGridLogic.isAreaFree(page, hoverCellX, hoverCellY, item.spanX, item.spanY, item.appWidgetId)
      dragPreviewPaint.color = if (isFree) 0x664CAF50 else 0x66F44336
      val left = paddingLeft + hoverCellX * step
      val top = paddingTop + hoverCellY * step
      val right = left + item.spanX * step
      val bottom = top + item.spanY * step
      dragPreviewRect.set(left, top, right, bottom)
      canvas.drawRoundRect(dragPreviewRect, cornerRadius, cornerRadius, dragPreviewPaint)
    }
  }

  internal data class BindKey(
    val pageId: Long,
    val itemsSignature: String,
    val columnCount: Int,
    val rowCount: Int,
  )

  companion object {
    const val CELL_GAP_DP = 0f
    const val EDIT_MODE_BUFFER_ROWS = 5

    /**
     * 全局「分帧建卡」代际号：一次自增即可让所有页面上挂着的递归 post 停下。
     *
     * 收起面板时调用 [cancelAllPendingBinds]，避免窗口已经摘掉之后
     * 还在继续建卡（那会在收起后继续占用主线程）。
     */
    @Volatile
    private var bindGeneration = 0

    fun cancelAllPendingBinds() {
      bindGeneration++
    }
  }
}
