package com.slideindex.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.ui.ActionIconBitmap
import com.slideindex.app.util.GestureActionIconBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 面板卡片里「动作 / 快捷方式 / 自定义图标」的异步图标加载 + 缓存。
 *
 * 为什么需要它：这三种图标原先都在 **主线程** 同步解析 —— 动作图标要把矢量渲染成位图，
 * 快捷方式要走一次 `LauncherApps.getShortcuts()` 的 binder 往返，自定义图标可能还要解码图片。
 * 面板空着时没有卡片所以"秒开"，一旦放了几张卡，每张卡各阻塞一次主线程，
 * 呼出/收回就会"顿一下"（实测是卡片越多越明显）。
 *
 * 这里统一改成后台线程解析 + LruCache 复用，回调一律在主线程；
 * 调用方（卡片）自行校验视图是否还属于同一目标，避免列表复用错图。
 */
internal object WidgetCellIconLoader {
  private const val ICON_PX = 132
  private const val MAX_ENTRIES = 48

  private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {}
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mainHandler = Handler(Looper.getMainLooper())

  fun clear() {
    cache.evictAll()
  }

  fun peek(key: String): Bitmap? = if (key.isBlank()) null else cache.get(key)

  /** 动作图标（内置 / 图标库 / 应用回退），键 = 动作负载。 */
  fun actionKey(action: GestureAction): String = "a:${action.type.id}:${action.payload}"

  /** 快捷方式图标，键 = 包名 + shortcutId。 */
  fun shortcutKey(packageName: String, shortcutId: String): String = "s:$packageName/$shortcutId"

  /** 用户自定义图标（图标库 / 文字 / 图片）。 */
  fun customKey(key: String, sizePx: Int): String = "c:$key:$sizePx"

  fun loadAction(
    context: Context,
    action: GestureAction,
    sizePx: Int,
    onLoaded: (Bitmap) -> Unit,
  ) {
    val key = actionKey(action)
    peek(key)?.let { onLoaded(it); return }
    val appContext = context.applicationContext
    scope.launch {
      val bitmap = runCatching {
        GestureActionIconBitmap.get(
          action = action,
          sizePx = sizePx,
          tintArgb = android.graphics.Color.WHITE,
          outlined = true,
          withPlate = true,
        )
      }.getOrNull() ?: return@launch
      cache.put(key, bitmap)
      mainHandler.post { onLoaded(bitmap) }
    }
  }

  fun loadShortcut(
    context: Context,
    packageName: String,
    shortcutId: String,
    onLoaded: (Bitmap) -> Unit,
  ) {
    val key = shortcutKey(packageName, shortcutId)
    peek(key)?.let { onLoaded(it); return }
    val appContext = context.applicationContext
    scope.launch {
      val bitmap = runCatching {
        val launcherApps =
          appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? android.content.pm.LauncherApps
        val query = android.content.pm.LauncherApps.ShortcutQuery().apply {
          setPackage(packageName)
          setShortcutIds(listOf(shortcutId))
          setQueryFlags(
            android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
              android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
              android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
          )
        }
        val info = launcherApps?.getShortcuts(query, android.os.Process.myUserHandle())?.firstOrNull()
          ?: return@launch
        val density = appContext.resources.displayMetrics.densityDpi
        val drawable = launcherApps.getShortcutIconDrawable(info, density) ?: return@launch
        val bitmap = android.graphics.Bitmap.createBitmap(ICON_PX, ICON_PX, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        bitmap
      }.getOrNull() ?: return@launch
      cache.put(key, bitmap)
      mainHandler.post { onLoaded(bitmap) }
    }
  }

  fun loadCustomIcon(
    context: Context,
    iconKey: String,
    action: GestureAction?,
    sizePx: Int,
    onLoaded: (Bitmap) -> Unit,
  ) {
    if (iconKey.isBlank()) return
    val key = customKey(iconKey, sizePx)
    peek(key)?.let { onLoaded(it); return }
    val appContext = context.applicationContext
    scope.launch {
      val bitmap = runCatching {
        ActionIconBitmap.loadByKey(
          context = appContext,
          key = iconKey,
          sizePx = sizePx,
          tintArgb = android.graphics.Color.WHITE,
          withPlate = true,
          action = action,
        )
      }.getOrNull() ?: return@launch
      cache.put(key, bitmap)
      mainHandler.post { onLoaded(bitmap) }
    }
  }

  /**
   * 快捷方式图标：按 `GestureShortcutPayload` 解码后走 `CornerSlotIconBitmap`
   * （边角轮盘用的同一个解析器），动态 / 组件 / intent 三种形态都能出图；
   * 拿不到专属图标时它会自己回落到所属应用图标，不需要调用方再兜一层。
   */
  fun loadShortcutIcon(
    context: Context,
    payloadKey: String,
    sizePx: Int,
    onLoaded: (Bitmap) -> Unit,
  ) {
    if (payloadKey.isBlank()) return
    val key = "sc:$payloadKey:$sizePx"
    peek(key)?.let { onLoaded(it); return }
    val appContext = context.applicationContext
    scope.launch {
      val action = com.slideindex.app.gesture.GestureAction.LaunchShortcut(
        payloadKey = payloadKey,
        label = "",
      )
      val bitmap = runCatching {
        com.slideindex.app.overlay.corner.CornerSlotIconBitmap.get(
          context = appContext,
          action = action,
          sizePx = sizePx,
          tintArgb = android.graphics.Color.WHITE,
          plateFallback = true,
        )
      }.getOrNull() ?: return@launch
      cache.put(key, bitmap)
      mainHandler.post { onLoaded(bitmap) }
    }
  }
}
