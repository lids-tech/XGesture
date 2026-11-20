package com.slideindex.app.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isNotEmpty
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.graphics.Path
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Card shell wrapping [ScalableFrameLayout] with delete / resize chrome.
 */
@SuppressLint("ViewConstructor") // Programmatically created widget card
class WidgetCardContainer(
  context: Context,
  initialItem: WidgetPanelItem,
  hostView: AppWidgetHostView?,
) : FrameLayout(context) {

  var item: WidgetPanelItem = initialItem
    private set

  /**
   * 应用 / 动作 / 快捷方式格子里真正带点击逻辑的那层视图。
   *
   * 长按二级菜单松手「不选任何项」时要走一次普通点击（应用 = 小窗打开，其它 = 原行为），
   * 而点击逻辑挂在内部那层视图上，外部 `performClick()` 打不到，所以留个引用。
   */
  private var contentClickView: View? = null

  /** 触发该格子自己的点击行为（与手指点一下等价）。 */
  fun performContentClick() {
    (contentClickView ?: this).performClick()
  }
  var onDelete: (() -> Unit)? = null
  var onResize: ((spanX: Int, spanY: Int) -> Unit)? = null
  var onConfigure: (() -> Unit)? = null

  /**
   * 编辑模式下点「⋯」→ 配置该格子的长按二级菜单。
   *
   * 只对应用 / 动作 / 快捷方式生效（小组件卡没有二级菜单）。
   */
  var onEditSubActions: (() -> Unit)? = null
  var onInteractionActiveChange: ((Boolean) -> Unit)? = null

  private val scalableFrame: ScalableFrameLayout
  private val loadingPlaceholder: WidgetLoadingPlaceholder
  private val deleteButton: View
  private val resizeHandle: ResizeHandleView
  private val configureButton: View
  /** 编辑模式下的「⋯」：配置该格子的长按二级菜单（仅应用 / 动作 / 快捷方式可见）。 */
  private val subActionsButton: android.widget.TextView
  private var editModeEnabled = false
  private var previewSpanX = item.spanX
  private var previewSpanY = item.spanY
  private var previewWidthPx = 0
  private var previewHeightPx = 0
  private var previewingResize = false
  private var resizeSnapAnimator: ValueAnimator? = null
  private var hostEverBound = false
  private var pendingHostLayoutCommit: Runnable? = null
  private var lastDebouncedCommitSpanX = -1
  private var lastDebouncedCommitSpanY = -1
  /** 应用/快捷方式/动作格子里的「图标 + 名称」两层视图；小组件卡为 null。 */
  private var appContentView: android.widget.LinearLayout? = null
  private var appIconView: androidx.appcompat.widget.AppCompatImageView? = null
  private var appLabelView: android.widget.TextView? = null
  /** 是否显示名称（由 [WidgetCanvasLayout.appLabelsVisible] 下发）。 */
  private var showAppLabels: Boolean = true

  private val density = resources.displayMetrics.density
  private val cardCornerRadiusPx = 16f * density
  private val editOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = 0x66FFFFFF
    style = Paint.Style.STROKE
    strokeWidth = 1.2f * density
  }

  init {
    clipChildren = false
    clipToPadding = false
    clipToOutline = false

    // 格子容器底色（编辑器里可设；0 = 不画，保持透明）。
    if (item.containerColorArgb != 0L) {
      background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        setColor(item.containerColorArgb.toInt())
        cornerRadius = cardCornerRadiusPx
      }
    }

    loadingPlaceholder = WidgetLoadingPlaceholder(context)
    scalableFrame = ScalableFrameLayout(context)

    if (item.itemType == ITEM_TYPE_APP || item.itemType == ITEM_TYPE_SHORTCUT || item.itemType == ITEM_TYPE_ACTION) {
      loadingPlaceholder.visibility = GONE
      scalableFrame.visibility = GONE
      val appView = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val iconView = object : androidx.appcompat.widget.AppCompatImageView(context) {
          override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (item.cloneUserId > 0) {
              com.slideindex.app.util.CloneBadge.draw(
                canvas,
                width / 2f,
                height / 2f,
                minOf(width, height).toFloat(),
                circular = false,
              )
            }
            // 快捷方式角标（闪电）：与快捷轮盘 / 圆环启动器同一渲染器，
            // 用以区分「这是跳进应用某功能的快捷方式」和「普通打开应用」。
            if (item.itemType == ITEM_TYPE_SHORTCUT) {
              com.slideindex.app.overlay.ShortcutBadgeRenderer.draw(
                canvas,
                width / 2f,
                height / 2f,
                minOf(width, height).toFloat(),
                1f,
                resources.displayMetrics.density,
              )
            }
          }
        }.apply {
          if (item.iconColorArgb != 0L &&
            (item.iconKey.isNotBlank() || item.itemType == ITEM_TYPE_ACTION)
          ) {
            setColorFilter(item.iconColorArgb.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
          }
          // ── 图标加载全部走后台 + 缓存（WidgetCellIconLoader）──
          // 原先动作图标（矢量渲染）、快捷方式图标（binder 往返）、自定义图标（可能解码图片）
          // 都在构造期同步解析：面板空着没事，放几张卡之后呼出/收回就"顿一下"。
          val iconSizePx = (44 * density).roundToInt()
          // 图标颜色（编辑器可设）：只给文字 / 矢量图标的“字”着色 —— 渲染时传 tint，
          // 而不是整张位图 SRC_IN（那会把底色也染掉，文字图标就看不见字了）。
          val iconTint = item.iconColorArgb.takeIf { it != 0L }?.toInt()
          val action = if (item.itemType == ITEM_TYPE_ACTION) {
            com.slideindex.app.launcher.QuickLauncherItemCodec.parseActionPayload(item.intentUri)
          } else {
            null
          }
          // 自定义图标优先：缓存命中就直接用，没命中后台加载后回填。
          if (item.iconKey.isNotBlank()) {
            // 动图图标（GIF / 动态 WebP）：用 Drawable 呈现，才能真正动起来。
            val animatedPath = item.iconKey
              .takeIf { com.slideindex.app.ui.ActionIconKeyCodec.isFile(it) }
              ?.removePrefix("file:")
              ?.takeIf { com.slideindex.app.ui.AnimatedIconSupport.looksAnimated(it) }
            if (animatedPath != null) {
              val animatedTag = "anim:${animatedPath}:$iconSizePx"
              tag = animatedTag
              WidgetCellIconLoader.loadAnimatedIcon(context, animatedPath) { drawable ->
                if (tag == animatedTag) {
                  scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                  setImageDrawable(drawable)
                }
              }
              return@apply
            }
            val customKey = WidgetCellIconLoader.customKey(item.iconKey, iconSizePx, iconTint ?: android.graphics.Color.WHITE)
            val cached = WidgetCellIconLoader.peek(customKey)
            if (cached != null) {
              setImageDrawable(cached.toDrawable(resources))
              return@apply
            }
            tag = customKey
            WidgetCellIconLoader.loadCustomIcon(
              context = context,
              iconKey = item.iconKey,
              action = action,
              sizePx = iconSizePx,
              tintArgb = iconTint ?: android.graphics.Color.WHITE,
              // 文字图标不画底板：只让字形着色（「只有 H 变红」）。
              withPlate = !item.iconKey.startsWith("t:"),
            ) { bitmap ->
              if (tag == customKey) setImageDrawable(bitmap.toDrawable(resources))
            }
            return@apply
          }
          // ② 动作 / 快捷方式：统一走**快捷轮盘同款**解析器
          //（QuickWheelIconResolver）：快捷方式会按「应用内直达目录」查真实图标，
          // 查不到才回落宿主应用图标；非这两类动作才用矢量动作图标。
          // 单一路径 → 不会出现「应用图标 ↔ 右上箭头」来回跳。
          val wheelAction = when {
            item.itemType == ITEM_TYPE_ACTION -> action
            // 面板存的是「快速启动器条目」的 payload（包名\u001C快捷id / i: / is: / c:），
            // 统一用 WidgetPanelShortcutAction 还原成可执行动作（与轮盘添加时同一套规则）。
            item.itemType == ITEM_TYPE_SHORTCUT -> WidgetPanelShortcutAction.fromItem(item)
            else -> null
          }
          if (wheelAction != null) {
            val key = "wheel:${"${wheelAction.type.id}"}:${"${wheelAction.payload}"}:$iconSizePx"
            val cached = WidgetCellIconLoader.peek(key)
            if (cached != null) {
              setImageDrawable(cached.toDrawable(resources))
            } else {
              tag = key
              WidgetCellIconLoader.loadWheelIcon(context, wheelAction, iconSizePx) { bitmap ->
                if (tag == key) setImageDrawable(bitmap.toDrawable(resources))
              }
            }
          } else if (item.packageName.isNotEmpty()) {
            // 应用格子只有一种图标 → 走应用图标缓存（同样单一加载）。
            val iconPackage = item.packageName
            tag = iconPackage
            WidgetAppIconCache.load(context, iconPackage) { bitmap ->
              if (tag == iconPackage) setImageDrawable(bitmap.toDrawable(resources))
            }
          }
        }
        val iconSize = (44 * density).roundToInt()
        addView(iconView, LayoutParams(iconSize, iconSize))

        val labelView = android.widget.TextView(context).apply {
          text = item.label
          setTextColor(if (item.labelColorArgb != 0L) item.labelColorArgb.toInt() else Color.WHITE)
          setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
          gravity = Gravity.CENTER
          maxLines = 1
          ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val labelParams = LayoutParams(
          LayoutParams.WRAP_CONTENT,
          LayoutParams.WRAP_CONTENT,
        ).apply {
          topMargin = (4 * density).roundToInt()
        }
        addView(labelView, labelParams)
        appContentView = this
        appIconView = iconView
        appLabelView = labelView
      }
      addView(appView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
        gravity = Gravity.CENTER
      })
      contentClickView = appView
      appView.setOnClickListener {
        if (!editModeEnabled) {
          if (item.itemType == ITEM_TYPE_ACTION) {
            val action = com.slideindex.app.launcher.QuickLauncherItemCodec.parseActionPayload(item.intentUri)
            if (action != null) {
              com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
              // ⚠️ 必须走和快捷轮盘/浮球同一个执行器：
              // 直接调 AccessibilityService.perform 时，「快捷轮盘 / 快捷启动器 / 小组件面板」
              // 这类需要浮层宿主与锚点的动作收不到上下文，表现就是"点了没反应"。
              val settings = com.slideindex.app.overlay.panels.PanelRuntimeHooks
                .settingsSnapshot?.invoke(context)
              val runner = com.slideindex.app.overlay.panels.PanelRuntimeHooks.gestureActionRunner
                ?: com.slideindex.app.overlay.panels.PanelRuntimeHooks.defaultGestureRunner
              if (settings != null && runner != null) {
                // ⚠️ 必须传**屏幕坐标**：快捷轮盘这类动作按呼出点定位，
                // 传 null 会落到默认锚点（真机反馈"从面板打开的轮盘位置不对"）。
                val location = IntArray(2)
                getLocationOnScreen(location)
                val anchorX = location[0] + width / 2f
                val anchorY = location[1] + height / 2f
                runCatching { runner.run(action, settings, false, anchorX, anchorY, null) }
              } else {
                com.slideindex.app.service.SlideIndexAccessibilityService.perform(action)
              }
            }
          } else if (item.itemType == ITEM_TYPE_SHORTCUT) {
            // ⚠️ 不在这里先 dismiss：先收面板会让这次启动变成"无可见窗口"的后台启动，
            // 部分 ROM 直接拦掉（真机反馈"点按打不开、长按却能打开"——长按那条路
            // 就是先执行再收面板）。统一改成「先执行，成功后收面板」。
            // ⚠️ 和「动作」格子一样，必须走 gestureActionRunner（= 快捷轮盘/浮球那条链）：
            // 面板原先自己调 launcherApps.startShortcut + Intent.parseUri 兜底，
            // 只覆盖"动态快捷方式"一种形态 ——
            // 「我的直达 / 预设快捷方式库」存的是自描述 payload（c: / i: / 包名\u001Cid），
            // startShortcut 找不到、parseUri 又解析不了 → 点了没反应。
            // 执行器里 launchGestureShortcut 会把四种形态（动态/组件/intent/多 intent）
            // 全部解码后分别启动，并带 root / shell / 小窗等兜底。
            // 面板存的是「快速启动器条目」的 payload（包名\u001C快捷id / i: / is: / c:），
            // 而 GestureShortcutPayload 只认自己那套编码 —— 日志实测
            //   pkg=com.niven.translator id=fullscreen payloadKind=UNDECODABLE
            // 就是这个错位：payload 本身是好的，只是按错的解码器去解。
            // 统一用 WidgetPanelShortcutAction（= 轮盘添加时的转换规则）还原成可执行动作。
            // ⚠️ 快捷方式必须按「真实快捷方式」解析（与轮盘添加同源）：
            // 只按 component 启动会丢 extras（「开始录音」→ 只开界面不录音）。
            // resolveAsync 会先查 AppShortcutLoader 拿真实 intent，再回退各种编码。
            val settings = com.slideindex.app.overlay.panels.PanelRuntimeHooks
              .settingsSnapshot?.invoke(context)
            val runner = com.slideindex.app.overlay.panels.PanelRuntimeHooks.gestureActionRunner
              ?: com.slideindex.app.overlay.panels.PanelRuntimeHooks.defaultGestureRunner
            if (settings == null || runner == null) {
              com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
              runCatching {
                val launcherApps =
                  context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? android.content.pm.LauncherApps
                launcherApps?.startShortcut(
                  item.packageName,
                  item.shortcutId,
                  null,
                  null,
                  android.os.Process.myUserHandle(),
                )
              }
              return@setOnClickListener
            }
            val location = IntArray(2)
            getLocationOnScreen(location)
            val anchorX = location[0] + width / 2f
            val anchorY = location[1] + height / 2f
            WidgetPanelShortcutAction.resolveAsync(context, item) { action ->
              val decoded = action?.let { act ->
                runCatching {
                  com.slideindex.app.gesture.GestureShortcutPayload.decode(act.payloadKey)
                }.getOrNull()
              }
              android.util.Log.i(
                "WidgetPanelShortcut",
                "tap shortcut: pkg=" + item.packageName + " id=" + item.shortcutId +
                  " payloadKind=" + (decoded?.javaClass?.simpleName ?: "UNDECODABLE") +
                  " action=" + action?.payloadKey?.take(48),
              )
              if (action != null && decoded != null) {
                val run = runCatching {
                  runner.run(action, settings, false, anchorX, anchorY, null)
                }
                if (run.isSuccess) {
                  com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
                }
              }
            }
          } else if (item.packageName.isNotEmpty()) {
            com.slideindex.app.overlay.WidgetPopupOverlayWindow.dismiss()
            if (item.cloneUserId > 0) {
              // 应用分身：那是另一个 user 下的实例，必须跨 user 启动
              // （有特权走 shell，没有特权走透明跳板），不能按主实例的 launch intent 起。
              runCatching {
                com.slideindex.app.util.CloneAppLauncher.launch(
                  context,
                  item.packageName,
                  item.cloneUserId,
                )
              }
            } else {
              runCatching {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(item.packageName)
                  ?: android.content.Intent().apply {
                    component = android.content.ComponentName(item.packageName, item.className)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                context.startActivity(launchIntent)
              }
            }
          }
        }
      }
    } else if (hostView != null) {
      scalableFrame.bindWidget(hostView, item.appWidgetId, item.spanX, item.spanY)
      loadingPlaceholder.visibility = GONE
      hostEverBound = true
    } else {
      // 单进程后宿主视图与编辑器同进程：调用方没传视图时自己取一次，
      // 否则编辑器永远停在占位卡（以前只有 :overlay 进程能创建宿主视图）。
      //
      // ⚠️ 这里必须**同步**取：改成 post 异步后，窗口先出现、小组件区域先是空白，
      // 过一帧才补上内容（真机反馈"其他格子秒出，只有小组件位置空一下"）。
      // 图标解析已经全部异步化，剩下的 obtainHostView 只是 binder + 建视图，
      // 同步做完反而让首帧就是完整面板。
      val selfHostView = runCatching {
        WidgetPopupHost.startListening(context)
        WidgetPopupHost.obtainHostView(context, item.appWidgetId)
      }.getOrNull()
      if (selfHostView != null && item.itemType == ITEM_TYPE_WIDGET) {
        scalableFrame.visibility = VISIBLE
        scalableFrame.bindWidget(selfHostView, item.appWidgetId, item.spanX, item.spanY)
        loadingPlaceholder.visibility = GONE
        hostEverBound = true
        updateScalableTargetFromContainer()
        scalableFrame.commitHostLayout()
      } else {
        loadingPlaceholder.visibility = VISIBLE
      }
    }

    addView(loadingPlaceholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    addView(scalableFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

    if (hostView != null && item.itemType == ITEM_TYPE_WIDGET) {
      attachHostWhenReady(hostView)
    }

    val chromeBtnSize = (20 * density).roundToInt()
    val chromeMargin = (2 * density).roundToInt()
    deleteButton = View(context).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFFE53935.toInt())
      }
      val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.6f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
      }
      setWillNotDraw(false)
      tag = crossPaint
      setOnClickListener { onDelete?.invoke() }
    }
    addView(
      deleteButton,
      LayoutParams(chromeBtnSize, chromeBtnSize).apply {
        gravity = Gravity.TOP or Gravity.END
        topMargin = chromeMargin
        marginEnd = chromeMargin
      },
    )

    resizeHandle = ResizeHandleView(context).apply {
      @SuppressLint("ClickableViewAccessibility") // Resize drag handle; not a click target
      setOnTouchListener(ResizeTouchListener())
    }
    val handleSize = chromeBtnSize
    addView(
      resizeHandle,
      LayoutParams(handleSize, handleSize).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        bottomMargin = chromeMargin
        marginEnd = chromeMargin
      },
    )

    configureButton = View(context).apply {
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC333333.toInt())
      }
      setWillNotDraw(false)
      setOnClickListener { onConfigure?.invoke() }
    }
    addView(
      configureButton,
      LayoutParams(chromeBtnSize, chromeBtnSize).apply {
        gravity = Gravity.TOP or Gravity.START
        topMargin = chromeMargin
        marginStart = chromeMargin
      },
    )

    // 「⋯」二级菜单配置按钮：应用 / 动作 / 快捷方式格子在编辑模式下的入口。
    // 与删除按钮对称放右上（应用格子顶部右侧原本只有删除），避免和小部件卡的配置键冲突。
    subActionsButton = android.widget.TextView(context).apply {
      text = "⋯"
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
      setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC3478F6.toInt())
      }
      setOnClickListener { onEditSubActions?.invoke() }
    }
    addView(
      subActionsButton,
      LayoutParams(chromeBtnSize, chromeBtnSize).apply {
        gravity = Gravity.TOP or Gravity.END
        topMargin = chromeMargin
        marginEnd = chromeMargin + chromeBtnSize + chromeMargin
      },
    )

    setEditMode(false)
  }

  /** 全局「显示图标名称」开关变化时由画布下发；只改自己的排版，不动 span/网格。 */
  fun setAppLabelsVisible(visible: Boolean) {
    if (showAppLabels == visible) return
    showAppLabels = visible
    requestLayout()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    // 必须在测量子视图之前算好：格子是正方形，图标 + 4dp 间距 + 一行 11sp 名称
    // 需要 63~64dp；格子更小时名称会被下一行图标裁掉（用户反馈的「名字被挡住」）。
    // 这里按实测行高缩小图标；缩到下限仍放不下就只画图标。
    applyAppCellSizing(
      cardWidthPx = MeasureSpec.getSize(widthMeasureSpec),
      cardHeightPx = MeasureSpec.getSize(heightMeasureSpec),
    )
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }

  private fun applyAppCellSizing(cardWidthPx: Int, cardHeightPx: Int) {
    val icon = appIconView ?: return
    val label = appLabelView ?: return
    val content = appContentView ?: return
    if (cardWidthPx <= 0 || cardHeightPx <= 0) return

    val spanX = item.spanX.coerceAtLeast(1)
    val cellWidthPx = (cardWidthPx / spanX).coerceAtLeast(1)
    val gapPx = (4 * density).roundToInt()
    val maxIconPx = minOf(
      (44 * density).roundToInt(),
      (cellWidthPx - (2 * density).roundToInt()).coerceAtLeast(1),
    )
    val minIconPx = (20 * density).roundToInt().coerceAtMost(maxIconPx)

    label.measure(
      MeasureSpec.makeMeasureSpec(cellWidthPx, MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
    )
    val labelHeightPx = label.measuredHeight
    var showLabel = showAppLabels && label.text.isNotEmpty() && labelHeightPx > 0
    var iconSizePx = maxIconPx
    if (showLabel) {
      val fitPx = cardHeightPx - gapPx - labelHeightPx
      if (fitPx >= minIconPx) {
        iconSizePx = minOf(fitPx, maxIconPx)
      } else {
        // 显示开关开着但格子确实放不下：宁可只画图标，也不让文字被裁。
        showLabel = false
      }
    }
    if (!showLabel) {
      iconSizePx = minOf(
        maxIconPx,
        (cardHeightPx - (4 * density).roundToInt()).coerceAtLeast(1),
      ).coerceAtLeast(minIconPx)
    }

    val params = icon.layoutParams
    if (params.width != iconSizePx || params.height != iconSizePx) {
      params.width = iconSizePx
      params.height = iconSizePx
      icon.layoutParams = params
    }
    val labelVisibility = if (showLabel) View.VISIBLE else View.GONE
    if (label.visibility != labelVisibility) label.visibility = labelVisibility
    // 隐藏文字后，名称改由容器朗读（否则无障碍只剩一个无名图标）。
    val description = if (showLabel) null else item.label.ifBlank { null }
    if (content.contentDescription != description) content.contentDescription = description
  }

  fun syncItem(newItem: WidgetPanelItem) {
    if (item == newItem) return
    val iconChanged = item.iconKey != newItem.iconKey
    val labelChanged = item.label != newItem.label
    item = newItem
    previewSpanX = newItem.spanX
    previewSpanY = newItem.spanY
    if (labelChanged) {
      appLabelView?.text = newItem.label
    }
    // 图标 key 变了要重新取图：只改 requestLayout 的话图标会一直是旧的。
    if (iconChanged) refreshAppCellIcon()
    requestLayout()
  }

  /** 按当前 `item.iconKey` 重新解析一级格子的图标（自定义优先，否则走原逻辑）。 */
  private fun refreshAppCellIcon() {
    val iconView = appIconView ?: return
    if (item.iconKey.isBlank()) return
    val sizePx = (44 * density).roundToInt()
    val action = com.slideindex.app.launcher.QuickLauncherItemCodec.parseActionPayload(item.intentUri)
    val custom = runCatching {
      com.slideindex.app.ui.ActionIconBitmap.loadByKey(
        context = context,
        key = item.iconKey,
        sizePx = sizePx,
        tintArgb = Color.WHITE,
        withPlate = true,
        action = action,
      )
    }.getOrNull()
    if (custom != null) {
      iconView.setImageDrawable(custom.toDrawable(resources))
    }
  }

  fun attachHostView(hostView: AppWidgetHostView) {
    loadingPlaceholder.visibility = GONE
    scalableFrame.visibility = VISIBLE
    scalableFrame.bindWidget(hostView, item.appWidgetId, item.spanX, item.spanY)
    hostEverBound = true
    updateHostLongClickHandling(editModeEnabled)
    updateScalableTargetFromContainer()
    scalableFrame.commitHostLayout()
  }

  private fun ensureHostViewAttached() {
    if (scalableFrame.isNotEmpty()) return
    if (!hostEverBound) return
    val hostView = WidgetPopupHost.obtainHostView(context, item.appWidgetId) ?: return
    loadingPlaceholder.visibility = GONE
    scalableFrame.visibility = VISIBLE
    scalableFrame.bindWidget(hostView, item.appWidgetId, layoutSpanX(), layoutSpanY())
    updateHostLongClickHandling(editModeEnabled)
    updateScalableTargetFromContainer()
    scalableFrame.commitHostLayout(force = true)
  }

  private fun attachHostWhenReady(hostView: AppWidgetHostView) {
    loadingPlaceholder.visibility = GONE
    scalableFrame.visibility = VISIBLE
    hostEverBound = true
    updateScalableTargetFromContainer()
    scalableFrame.commitHostLayout()
  }

  fun setEditMode(enabled: Boolean) {
    editModeEnabled = enabled
    if (!enabled) clearResizePreview()
    deleteButton.visibility = if (enabled) VISIBLE else GONE
    if (item.itemType == ITEM_TYPE_APP || item.itemType == ITEM_TYPE_SHORTCUT) {
      resizeHandle.visibility = GONE
      configureButton.visibility = GONE
    } else if (item.itemType == ITEM_TYPE_ACTION) {
      resizeHandle.visibility = GONE
      configureButton.visibility = GONE
    } else {
      resizeHandle.visibility = if (enabled) VISIBLE else GONE
      scalableFrame.setWidgetTouchEnabled(true)
      updateHostLongClickHandling(enabled)

      val info = WidgetPopupHost.providerInfo(context, item.appWidgetId)
      val hasConfigure = info?.configure != null
      configureButton.visibility = if (enabled && hasConfigure) VISIBLE else GONE
    }
    // 二级菜单入口：只有应用 / 动作 / 快捷方式有（小组件卡没有二级菜单）。
    subActionsButton.visibility =
      if (enabled && item.itemType != ITEM_TYPE_WIDGET) VISIBLE else GONE
    invalidate()
  }

  private fun updateHostLongClickHandling(editMode: Boolean) {
    val hostView = if (scalableFrame.isNotEmpty()) scalableFrame.getChildAt(0) else null
    if (hostView == null) return
    // 浏览态不抢小组件自己的长按（有的 widget 长按会出菜单/配置），
    // 编辑态的拖动由画布处理，宿主这里也不再额外吞事件。
    hostView.isLongClickable = false
    hostView.setOnLongClickListener(null)
  }

  private fun drawEditOutline(canvas: Canvas) {
    val half = editOutlinePaint.strokeWidth / 2f
    val rect = RectF(half, half, width - half, height - half)
    canvas.drawRoundRect(rect, cardCornerRadiusPx, cardCornerRadiusPx, editOutlinePaint)
  }

  fun canContentScrollAtPoint(cardLocalX: Float, cardLocalY: Float, axis: Int, delta: Float): Boolean {
    val hostRoot = hostRoot() ?: return false
    val (hostX, hostY) = toHostLocalCoords(cardLocalX, cardLocalY) ?: return false
    return WidgetTouchScrollUtils.canScrollAtPoint(hostRoot, hostX, hostY, axis, delta)
  }

  fun shouldReceiveTouchAtPoint(cardLocalX: Float, cardLocalY: Float): Boolean {
    if (isTouchOnChrome(cardLocalX, cardLocalY)) return false
    val hostRoot = hostRoot() ?: return false
    val (hostX, hostY) = toHostLocalCoords(cardLocalX, cardLocalY) ?: return false
    return WidgetTouchScrollUtils.hasScrollableOrInteractiveContentAtPoint(hostRoot, hostX, hostY)
  }

  private fun hostRoot(): View? = if (scalableFrame.isNotEmpty()) scalableFrame.getChildAt(0) else null

  private fun toHostLocalCoords(cardLocalX: Float, cardLocalY: Float): Pair<Float, Float>? {
    val hostRoot = hostRoot() ?: return null
    val frameX = cardLocalX - scalableFrame.left + scalableFrame.scrollX
    val frameY = cardLocalY - scalableFrame.top + scalableFrame.scrollY
    val leftOffset = (scalableFrame.width - scalableFrame.renderWidthPx) / 2f
    val topOffset = (scalableFrame.height - scalableFrame.renderHeightPx) / 2f
    val scale = if (scalableFrame.scaleVal > 0f) scalableFrame.scaleVal else 1f
    // 旧算式（按原点缩放）保留一份，仅用于探针对比，确认后随探针一起删除。
    val oldHostX = (frameX - leftOffset - hostRoot.translationX) / scale
    val oldHostY = (frameY - topOffset - hostRoot.translationY) / scale
    // 子视图是「以自身中心为轴心」缩放的（见 ScalableFrameLayout.onLayout 的 pivot = renderWidthPx/2），
    // 逆变换必须绕中心算，不能按原点缩放再平移，否则 scale < 1 时会整体偏移。
    // 视觉中心 = leftOffset + renderWidthPx/2 = scalableFrame.width/2。
    val hostX = scalableFrame.renderWidthPx / 2f +
      (frameX - scalableFrame.width / 2f - hostRoot.translationX) / scale
    val hostY = scalableFrame.renderHeightPx / 2f +
      (frameY - scalableFrame.height / 2f - hostRoot.translationY) / scale
    WidgetPanelProbe.coordDelta(cardLocalX, cardLocalY, oldHostX, oldHostY, hostX, hostY, scale)
    return hostX to hostY
  }

  fun isTouchOnChrome(localX: Float, localY: Float): Boolean {
    if (deleteButton.isVisible && isPointInView(deleteButton, localX, localY)) return true
    if (resizeHandle.isVisible && isPointInView(resizeHandle, localX, localY)) return true
    if (configureButton.isVisible && isPointInView(configureButton, localX, localY)) return true
    if (subActionsButton.isVisible && isPointInView(subActionsButton, localX, localY)) return true
    return false
  }

  private fun isPointInView(view: View, localX: Float, localY: Float): Boolean {
    val pad = 6f * density
    return localX >= view.left - pad &&
      localX <= view.right + pad &&
      localY >= view.top - pad &&
      localY <= view.bottom + pad
  }

  private fun requestDisallowInterceptAllParents(disallow: Boolean) {
    var current: android.view.ViewParent? = parent
    while (current != null) {
      current.requestDisallowInterceptTouchEvent(disallow)
      current = current.parent
    }
  }

  fun isPreviewingResize(): Boolean = previewingResize

  fun previewLayoutWidthPx(gridStepPx: Int): Int {
    if (previewingResize && previewWidthPx > 0) return previewWidthPx
    return layoutSpanX() * gridStepPx.coerceAtLeast(1)
  }

  fun previewLayoutHeightPx(gridStepPx: Int): Int {
    if (previewingResize && previewHeightPx > 0) return previewHeightPx
    return layoutSpanY() * gridStepPx.coerceAtLeast(1)
  }

  fun updateScalableTargetFromContainer() {
    val totalCols = (parent as? WidgetCanvasLayout)?.pageColumnCount ?: 4
    scalableFrame.setTotalColumns(totalCols)
    scalableFrame.applySpan(layoutSpanX(), layoutSpanY())
  }

  fun applyResizePreview(spanX: Int, spanY: Int, widthPx: Int, heightPx: Int) {
    if (
      previewSpanX == spanX &&
      previewSpanY == spanY &&
      previewWidthPx == widthPx &&
      previewHeightPx == heightPx
    ) {
      return
    }
    previewSpanX = spanX
    previewSpanY = spanY
    previewWidthPx = widthPx
    previewHeightPx = heightPx
    if (!previewingResize) {
      previewingResize = true
      scalableFrame.setResizing(true)
    }
    (parent as? View)?.requestLayout()
    (parent as? WidgetCanvasLayout)?.notifyResizePreviewChanged()
    if (spanX != lastDebouncedCommitSpanX || spanY != lastDebouncedCommitSpanY) {
      lastDebouncedCommitSpanX = spanX
      lastDebouncedCommitSpanY = spanY
      scheduleDebouncedHostLayoutCommit()
    }
  }

  private fun scheduleDebouncedHostLayoutCommit() {
    pendingHostLayoutCommit?.let { removeCallbacks(it) }
    pendingHostLayoutCommit = Runnable {
      pendingHostLayoutCommit = null
      if (width <= 0 || height <= 0) return@Runnable
      updateScalableTargetFromContainer()
      scalableFrame.commitHostLayout(force = true)
    }.also { postDelayed(it, 80) }
  }

  private fun cancelPendingHostLayoutCommit() {
    pendingHostLayoutCommit?.let { removeCallbacks(it) }
    pendingHostLayoutCommit = null
    lastDebouncedCommitSpanX = -1
    lastDebouncedCommitSpanY = -1
  }

  fun previewResize(spanX: Int, spanY: Int, gridStepPx: Int) {
    val step = gridStepPx.coerceAtLeast(1)
    applyResizePreview(spanX, spanY, spanX * step, spanY * step)
  }

  fun clearResizePreview() {
    resizeSnapAnimator?.cancel()
    resizeSnapAnimator = null
    cancelPendingHostLayoutCommit()
    if (!previewingResize) return
    previewingResize = false
    previewSpanX = item.spanX
    previewSpanY = item.spanY
    previewWidthPx = 0
    previewHeightPx = 0
    scalableFrame.setResizing(false)
    scalableFrame.commitHostLayout()
    updateScalableTargetFromContainer()
    (parent as? View)?.requestLayout()
    (parent as? WidgetCanvasLayout)?.notifyResizePreviewChanged()
  }

  fun layoutSpanX(): Int = if (previewingResize) previewSpanX else item.spanX

  fun layoutSpanY(): Int = if (previewingResize) previewSpanY else item.spanY

  fun refreshWidgetLayout(force: Boolean = false) {
    ensureHostViewAttached()
    updateScalableTargetFromContainer()
    scalableFrame.commitHostLayout(force = force)
    requestLayout()
  }

  private fun runAfterNextLayout(block: () -> Unit) {
    if (isAttachedToWindow && width > 0 && height > 0 && !isLayoutRequested) {
      block()
      return
    }
    viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
      override fun onGlobalLayout() {
        viewTreeObserver.removeOnGlobalLayoutListener(this)
        block()
      }
    })
  }

  private fun finishResize(newSpanX: Int, newSpanY: Int, sizeChanged: Boolean, onComplete: (() -> Unit)? = null) {
    resizeSnapAnimator?.cancel()
    val canvas = parent as? WidgetCanvasLayout
    val step = canvas?.gridStepPx?.coerceAtLeast(1) ?: 1
    val targetW = newSpanX * step
    val targetH = newSpanY * step
    val startW = previewLayoutWidthPx(step)
    val startH = previewLayoutHeightPx(step)
    val needsSnapAnim = startW != targetW || startH != targetH

    if (needsSnapAnim) {
      previewSpanX = newSpanX
      previewSpanY = newSpanY
      resizeSnapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 120L
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
          val t = anim.animatedValue as Float
          previewWidthPx = (startW + (targetW - startW) * t).roundToInt()
          previewHeightPx = (startH + (targetH - startH) * t).roundToInt()
          (parent as? View)?.requestLayout()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: android.animation.Animator) {
            resizeSnapAnimator = null
            completeFinishResize(newSpanX, newSpanY, sizeChanged, onComplete)
          }

          override fun onAnimationCancel(animation: android.animation.Animator) {
            resizeSnapAnimator = null
            completeFinishResize(newSpanX, newSpanY, sizeChanged, onComplete)
          }
        })
        start()
      }
      return
    }
    completeFinishResize(newSpanX, newSpanY, sizeChanged, onComplete)
  }

  private fun completeFinishResize(
    newSpanX: Int,
    newSpanY: Int,
    sizeChanged: Boolean,
    onComplete: (() -> Unit)? = null,
  ) {
    cancelPendingHostLayoutCommit()
    if (sizeChanged) {
      syncItem(item.copy(spanX = newSpanX, spanY = newSpanY))
    } else {
      previewSpanX = item.spanX
      previewSpanY = item.spanY
    }
    previewWidthPx = 0
    previewHeightPx = 0
    previewingResize = false
    scalableFrame.setResizing(false)
    scalableFrame.resetHostSizeCache()
    (parent as? View)?.requestLayout()
    (parent as? WidgetCanvasLayout)?.notifyResizePreviewChanged()
    runAfterNextLayout {
      ensureHostViewAttached()
      updateScalableTargetFromContainer()
      scalableFrame.commitHostLayout(force = true)
      if (sizeChanged) {
        onResize?.invoke(newSpanX, newSpanY)
      }
      onComplete?.invoke()
    }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    if (width <= 0 || height <= 0) return
    ensureHostViewAttached()
    updateScalableTargetFromContainer()
    if (!previewingResize) {
      scalableFrame.commitHostLayout()
    }
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    if (editModeEnabled) {
      drawEditOutline(canvas)
    }
    if (editModeEnabled && deleteButton.isVisible) {
      val paint = deleteButton.tag as? Paint ?: return
      val cx = deleteButton.left + deleteButton.width / 2f
      val cy = deleteButton.top + deleteButton.height / 2f
      val r = deleteButton.width * 0.16f
      canvas.drawLine(cx - r, cy - r, cx + r, cy + r, paint)
      canvas.drawLine(cx + r, cy - r, cx - r, cy + r, paint)
    }
    if (editModeEnabled && configureButton.isVisible) {
      drawGearIcon(
        canvas,
        configureButton.left + configureButton.width / 2f,
        configureButton.top + configureButton.height / 2f,
        configureButton.width * 0.28f,
      )
    }
  }

  private fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = 1.2f * density
      strokeCap = Paint.Cap.ROUND
    }
    val toothCount = 8
    val outerR = radius
    val innerR = radius * 0.72f
    val holeR = radius * 0.32f
    val path = Path()
    for (i in 0 until toothCount) {
      val angle = Math.PI * 2 * i / toothCount
      val next = Math.PI * 2 * (i + 0.5) / toothCount
      val x1 = cx + cos(angle).toFloat() * outerR
      val y1 = cy + sin(angle).toFloat() * outerR
      val x2 = cx + cos(next).toFloat() * innerR
      val y2 = cy + sin(next).toFloat() * innerR
      if (i == 0) path.moveTo(x1, y1) else path.lineTo(x1, y1)
      path.lineTo(x2, y2)
    }
    path.close()
    canvas.drawPath(path, paint)
    canvas.drawCircle(cx, cy, holeR, paint)
  }

  private inner class ResizeTouchListener : OnTouchListener {
    private var startSpanX = item.spanX
    private var startSpanY = item.spanY
    private var startWidthPx = 0
    private var startHeightPx = 0
    private var startRawX = 0f
    private var startRawY = 0f
    private var gridStepPx = 0
    private var previewSpanX = item.spanX
    private var previewSpanY = item.spanY

    @SuppressLint("ClickableViewAccessibility") // Resize drag gesture; not a click
    override fun onTouch(v: View, event: MotionEvent): Boolean {
      if (!editModeEnabled) return false
      val parent = parent as? WidgetCanvasLayout ?: return false
      gridStepPx = parent.gridStepPx
      val step = gridStepPx.coerceAtLeast(1)
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          resizeSnapAnimator?.cancel()
          resizeSnapAnimator = null
          startSpanX = item.spanX
          startSpanY = item.spanY
          previewSpanX = startSpanX
          previewSpanY = startSpanY
          startWidthPx = startSpanX * step
          startHeightPx = startSpanY * step
          startRawX = event.rawX
          startRawY = event.rawY
          scalableFrame.resetHostSizeCache()
          applyResizePreview(startSpanX, startSpanY, startWidthPx, startHeightPx)
          onInteractionActiveChange?.invoke(true)
          requestDisallowInterceptAllParents(true)
          return true
        }
        MotionEvent.ACTION_MOVE -> {
          val rawDy = event.rawY - startRawY
          val rawH = (startHeightPx + rawDy).roundToInt().coerceAtLeast(step)
          val candidateSpanYRaw = ((rawH + step - 1) / step).coerceAtLeast(1)
          parent.ensureBufferRowsBelow(item.y + candidateSpanYRaw)
          val maxSpanX = parent.pageColumnCount - item.x
          val maxSpanY = (parent.pageRowCount - item.y).coerceAtLeast(1)
          val maxW = maxSpanX * step
          val maxH = maxSpanY * step
          val rawDx = event.rawX - startRawX
          val newW = (startWidthPx + rawDx).roundToInt().coerceIn(step, maxW)
          val newH = (startHeightPx + rawDy).roundToInt().coerceIn(step, maxH)
          val candidateSpanX = ((newW + step - 1) / step).coerceIn(1, maxSpanX)
          val candidateSpanY = ((newH + step - 1) / step).coerceIn(1, maxSpanY)
          val page = parent.currentPage ?: return true
          if (WidgetPanelGridLogic.isAreaFree(
              page,
              item.x,
              item.y,
              candidateSpanX,
              candidateSpanY,
              item.appWidgetId,
            )
          ) {
            previewSpanX = candidateSpanX
            previewSpanY = candidateSpanY
            applyResizePreview(candidateSpanX, candidateSpanY, newW, newH)
          }
          return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          requestDisallowInterceptAllParents(false)
          val newSpanX = previewSpanX
          val newSpanY = previewSpanY
          val sizeChanged = newSpanX != item.spanX || newSpanY != item.spanY
          finishResize(newSpanX, newSpanY, sizeChanged)
          onInteractionActiveChange?.invoke(false)
          return true
        }
      }
      return false
    }
  }

  private class ResizeHandleView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val armPx = 8f * density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xCC1A1A1A.toInt()
      strokeWidth = 2.8f * density
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      strokeWidth = 1.8f * density
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }

    init {
      setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
      super.onDraw(canvas)
      val right = width - 1.5f * density
      val bottom = height - 1.5f * density
      val top = bottom - armPx
      val left = right - armPx
      canvas.drawLine(right, top, right, bottom, outlinePaint)
      canvas.drawLine(left, bottom, right, bottom, outlinePaint)
      canvas.drawLine(right, top, right, bottom, paint)
      canvas.drawLine(left, bottom, right, bottom, paint)
    }
  }
}
