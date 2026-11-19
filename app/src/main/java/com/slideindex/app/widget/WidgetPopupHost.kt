package com.slideindex.app.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Shared [AppWidgetHost] for the widget popup overlay and the settings editor.
 * Host id is arbitrary but must stay stable across process lifetime.
 */
object WidgetPopupHost {
  private const val TAG = "WidgetPopupHost"

  @Volatile
  private var host: SlideIndexAppWidgetHost? = null

  @Volatile
  private var listening = false

  private val cachedHostViews = java.util.concurrent.ConcurrentHashMap<String, AppWidgetHostView>()

  /**
   * 宿主视图的缓存键 = **页面 id + AppWidget id**。
   *
   * 为什么必须带页面：同一个小组件可以被复制到多个面板页（"复制当前面板"）。
   * 如果按 appWidgetId 缓存，两页会抢同一个 AppWidgetHostView 实例 ——
   * 后加的一页把视图从上一页 removeView 抢走，表现就是：
   *  · 左右滑页时单元格内容错乱 / 整页空白 / 文字被放大；
   *  · 删掉复制页上的小组件，原页那个也跟着变"加载中"。
   * 按页隔离后每页各自持有自己的宿主视图，互不影响。
   */
  fun viewKey(pageId: Long, appWidgetId: Int): String = pageId.toString() + ":" + appWidgetId


  fun appWidgetHost(context: Context): SlideIndexAppWidgetHost {
    val appContext = context.applicationContext
    return host ?: synchronized(this) {
      host ?: SlideIndexAppWidgetHost(appContext).also { host = it }
    }
  }

  fun appWidgetManager(context: Context): AppWidgetManager =
    AppWidgetManager.getInstance(context.applicationContext)

  fun startListening(context: Context) {
    synchronized(this) {
      if (listening) return
      runCatching {
        appWidgetHost(context).startListening()
        listening = true
      }.onFailure { Log.e(TAG, "startListening failed", it) }
    }
  }

  fun stopListening(context: Context) {
    synchronized(this) {
      if (!listening) return
      runCatching {
        appWidgetHost(context).stopListening()
        listening = false
      }.onFailure { Log.e(TAG, "stopListening failed", it) }
    }
  }

  fun allocateAppWidgetId(context: Context): Int =
    appWidgetHost(context).allocateAppWidgetId()

  fun deleteAppWidgetId(context: Context, appWidgetId: Int) {
    val suffix = ":" + appWidgetId
    cachedHostViews.keys.filter { it == appWidgetId.toString() || it.endsWith(suffix) }
      .forEach { key ->
        cachedHostViews.remove(key)?.let { view ->
          runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        }
      }
    runCatching { appWidgetHost(context).deleteAppWidgetId(appWidgetId) }
      .onFailure { Log.w(TAG, "deleteAppWidgetId($appWidgetId) failed", it) }
  }

  /** 只保留这些页面的宿主视图缓存（页面被删掉后，它的视图缓存一并释放）。 */
  fun retainPages(pageIds: Set<Long>) {
    cachedHostViews.keys.filterNot { key ->
      val id = key.substringBefore(':').toLongOrNull()
      id != null && id in pageIds
    }.forEach { key ->
      cachedHostViews.remove(key)?.let { view ->
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
      }
    }
  }
  /** 页面被删除时释放该页的宿主视图（避免删除页的视图一直占内存/被抢）。 */
  fun releasePageViews(pageId: Long) {
    val prefix = pageId.toString() + ":"
    cachedHostViews.keys.filter { it.startsWith(prefix) }.forEach { key ->
      cachedHostViews.remove(key)?.let { view ->
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
      }
    }
  }

  fun obtainHostView(context: Context, appWidgetId: Int, cacheKey: String): AppWidgetHostView? {
    cachedHostViews[cacheKey]?.let { cached ->
      WidgetPanelProbe.hostViewObtained(appWidgetId, fromCache = true)
      (cached.parent as? ViewGroup)?.removeView(cached)
      return cached
    }
    val created = createView(context, appWidgetId, cacheKey) as? AppWidgetHostView
    WidgetPanelProbe.hostViewObtained(appWidgetId, fromCache = false)
    return created
  }

  /**
   * 会话级缓存清理：面板关闭、重新打开前调用。
   *
   * 为什么不能跨会话复用：`AppWidgetHostView` 一旦 detach，集合型小组件
   * （日程/邮件这类 `RemoteViewsAdapter`）的行级点击绑定会失效或停在上一代
   * RemoteViews 上——表现就是“面板里看得见内容，但点行只开 app / 点了没反应”。
   * 每次面板会话重建宿主视图，是目前最稳的解法。
   */
  fun clearCachedHostViews() {
    val views = cachedHostViews.values.toList()
    cachedHostViews.clear()
    views.forEach { view ->
      runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    }
  }

  fun providerInfo(context: Context, appWidgetId: Int): AppWidgetProviderInfo? =
    runCatching { appWidgetManager(context).getAppWidgetInfo(appWidgetId) }.getOrNull()

  /**
   * 预热宿主视图：给所有小组件提前把 [AppWidgetHostView] 建好放进缓存。
   *
   * 背景：呼出时同步建宿主视图会"顿一下"，异步建又会出现"先空白再补上"。
   * 预热走第三条路——**面板关掉之后**（主线程空闲、用户无感）把下一把要用的
   * 宿主视图建好，下次呼出全部命中缓存：既不顿也不空白。
   *
   * ⚠️ 必须在主线程调（View 创建不是线程安全的），调用方用 handler 调度。
   * 已在缓存里的 id 直接跳过。
   */
  fun prewarmHostViews(context: Context, pages: List<com.slideindex.app.widget.WidgetPanelPage>) {
    val appContext = context.applicationContext
    for (page in pages) {
      for (item in page.items) {
        if (item.itemType != com.slideindex.app.widget.ITEM_TYPE_WIDGET || item.appWidgetId <= 0) continue
        val key = viewKey(page.id, item.appWidgetId)
        if (cachedHostViews.containsKey(key)) continue
        runCatching { createView(appContext, item.appWidgetId, key) }
          .onFailure { Log.w(TAG, "prewarm($key) failed: ${it.message}") }
      }
    }
  }

  /**
   * 打开面板时让所有小组件重新拉一次数据。
   *
   * 只用一条通道：给 provider 显式发 `ACTION_APPWIDGET_UPDATE` 广播（带 id），
   * 让它的 `onUpdate` 重建 RemoteViews，再通过已 startListening 的宿主流回视图。
   *
   * ⚠️ 别加 `updateAppWidget(id, null)`：传空 RemoteViews 是把内容**清空**而不是
   * 请求刷新，真机表现是「打开面板组件一片空白 / 数据更旧」。
   * 也别翻转 options 去骗 `onAppWidgetOptionsChanged`：那个回调本意是尺寸变化，
   * 部分 provider 会按新尺寸重排，数据没刷新反而排版错乱。
   */
  fun refreshWidgets(context: Context, pages: List<com.slideindex.app.widget.WidgetPanelPage>) {
    val appContext = context.applicationContext
    val manager = appWidgetManager(appContext)
    var count = 0
    pages.asSequence()
      .flatMap { it.items.asSequence() }
      .mapNotNull { it.appWidgetId.takeIf { id -> id > 0 } }
      .distinct()
      .forEach { id ->
        runCatching {
          val info = manager.getAppWidgetInfo(id)
          val provider = info?.provider
          val intent = android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
          if (provider != null) intent.component = provider
          intent.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
          appContext.sendBroadcast(intent)
          count++
        }.onFailure { Log.w(TAG, "refreshWidgets($id) failed: ${it.message}") }
      }
    if (count > 0) Log.i(TAG, "refreshWidgets: 已请求刷新 $count 个小组件")
  }

  fun createView(context: Context, appWidgetId: Int, cacheKey: String): View? {
    val manager = appWidgetManager(context)
    val info = manager.getAppWidgetInfo(appWidgetId)
    if (info == null) {
      Log.w(TAG, "createView($appWidgetId): getAppWidgetInfo returned null")
      return null
    }
    val widgetHost = appWidgetHost(context)
    return runCatching { widgetHost.createView(context, appWidgetId, info) }
      .onFailure { Log.e(TAG, "createView($appWidgetId) failed", it) }
      .getOrNull()
      ?.also { view ->
        cachedHostViews[cacheKey] = view
      }
  }

  fun labelFor(context: Context, appWidgetId: Int): String {
    val info = providerInfo(context, appWidgetId) ?: return ""
    @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
    return info.loadLabel(context.packageManager)?.toString().orEmpty()
  }
}
