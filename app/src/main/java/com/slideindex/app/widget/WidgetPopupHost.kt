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

  private val cachedHostViews = java.util.concurrent.ConcurrentHashMap<Int, AppWidgetHostView>()

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
    cachedHostViews.remove(appWidgetId)?.let { view ->
      runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    }
    runCatching { appWidgetHost(context).deleteAppWidgetId(appWidgetId) }
      .onFailure { Log.w(TAG, "deleteAppWidgetId($appWidgetId) failed", it) }
  }

  fun obtainHostView(context: Context, appWidgetId: Int): AppWidgetHostView? {
    cachedHostViews[appWidgetId]?.let { cached ->
      WidgetPanelProbe.hostViewObtained(appWidgetId, fromCache = true)
      (cached.parent as? ViewGroup)?.removeView(cached)
      return cached
    }
    val created = createView(context, appWidgetId) as? AppWidgetHostView
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
   * 打开面板时让所有小组件重新拉一次数据。
   *
   * 宿主视图是会话级缓存：provider 的周期性更新（步数 / 天气 / 日程）到达时间
   * 与面板打开时机无关，复用旧视图就会显示**上一会话**的 RemoteViews。
   *
   * ⚠️ 这里不能用 `AppWidgetManager.updateAppWidget(id, null)`——空 RemoteViews 只是
   * 「清空内容」，不会让 provider 重跑 onUpdate（真机验证过：数值仍旧）。正确做法是
   * 给 provider 显式发一条 `ACTION_APPWIDGET_UPDATE` 广播，带上要刷新的 id；
   * provider 的 onUpdate 会重建 RemoteViews 并通过已 startListening 的宿主流回视图。
   */
  fun refreshWidgets(context: Context, pages: List<com.slideindex.app.widget.WidgetPanelPage>) {
    val appContext = context.applicationContext
    val manager = appWidgetManager(appContext)
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
        }.onFailure { Log.w(TAG, "refreshWidgets($id) failed: ${it.message}") }
      }
  }

  fun createView(context: Context, appWidgetId: Int): View? {
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
        cachedHostViews[appWidgetId] = view
      }
  }

  fun labelFor(context: Context, appWidgetId: Int): String {
    val info = providerInfo(context, appWidgetId) ?: return ""
    @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
    return info.loadLabel(context.packageManager)?.toString().orEmpty()
  }
}
