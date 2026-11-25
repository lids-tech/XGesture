package com.slideindex.app.widget

import android.content.Context
import com.slideindex.app.R

object WidgetPanelMutator {
  fun addPage(pages: List<WidgetPanelPage>): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val newId = (effective.maxOfOrNull { it.id } ?: 0L) + 1L
    effective.add(WidgetPanelPage(id = newId))
    return effective
  }

  /**
   * 复制整页（"现在面板" → 复制一份到末尾并切过去）。
   *
   * 应用 / 动作 / 快捷方式格子的 id 是合成负数，复制时换新 id 避免和原页冲突；
   * 小组件格子的 id 是真实 AppWidget id，保持不变（这样复制出来的页显示同一个小组件）。
   */
  fun duplicatePage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    if (pageIndex !in effective.indices) return null
    val source = effective[pageIndex]
    val newPageId = (effective.maxOfOrNull { it.id } ?: 0L) + 1L
    val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context.applicationContext)
    var nextSynthetic = (effective.flatMap { it.items }
      .filter { it.appWidgetId < 0 }
      .minOfOrNull { it.appWidgetId } ?: -1) - 1
    // 复制页必须「绝对独立」：小组件要分配**新的 AppWidget id** 并绑定到同一个 provider。
    // 不能沿用原 id —— 同一个 AppWidget 只会有**一个**宿主视图实例
    // （AppWidgetHost 内部按 appWidgetId 记录视图），两页引用同 id 会互相覆盖更新，
    // 真机表现就是「复制页内容缺失 / 反复呼出有时正常有时缺失」。
    val copiedItems = source.items.map { item ->
      when {
        item.itemType == ITEM_TYPE_WIDGET && item.appWidgetId > 0 -> {
          val freshId = runCatching {
            val allocated = WidgetPopupHost.allocateAppWidgetId(context)
            val provider = appWidgetManager.getAppWidgetInfo(item.appWidgetId)?.provider
            if (provider != null &&
              appWidgetManager.bindAppWidgetIdIfAllowed(allocated, provider)
            ) {
              allocated
            } else {
              runCatching { WidgetPopupHost.deleteAppWidgetId(context, allocated) }
              -1
            }
          }.getOrDefault(-1)
          if (freshId > 0) {
            item.copy(appWidgetId = freshId)
          } else {
            // 绑定失败（无 BIND_APPWIDGET 权限）：跳过这个小组件，避免复制页把原页拖坏。
            null
          }
        }
        item.appWidgetId < 0 -> item.copy(appWidgetId = nextSynthetic--)
        else -> item
      }
    }.filterNotNull()
    effective.add(
      source.copy(
        id = newPageId,
        name = "",
        items = copiedItems,
      ),
    )
    return effective
  }
  fun removePage(pages: List<WidgetPanelPage>, pageIndex: Int): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    if (effective.size <= 1) return null
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    effective.removeAt(index)
    return effective
  }

  fun addWidgetToPage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    appWidgetId: Int,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages)
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val info = WidgetPopupHost.providerInfo(context, appWidgetId)
    if (info == null) {
      android.util.Log.e("WidgetPanelMutator", "addWidgetToPage failed: providerInfo is null for id $appWidgetId")
      android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(
          context,
          context.getString(R.string.widget_panel_add_widget_failed),
          android.widget.Toast.LENGTH_LONG,
        ).show()
      }
      return null
    }
    val (rawSpanX, rawSpanY) = WidgetSpanUtil.spanFromProviderInfo(info)
    val spanX = rawSpanX.coerceIn(1, page.columnCount)
    val spanY = rawSpanY.coerceAtLeast(1)
    val slot = WidgetPanelGridLogic.findFirstFreeSlot(page, spanX, spanY)
    if (slot == null) {
      android.util.Log.e("WidgetPanelMutator", "addWidgetToPage failed: no slot for span $spanX x $spanY")
      return null
    }
    val label = WidgetPopupHost.labelFor(context, appWidgetId)
    val item = WidgetPanelItem(
      appWidgetId = appWidgetId,
      x = slot.first,
      y = slot.second,
      spanX = spanX,
      spanY = spanY,
      label = label,
    )
    val updatedPage = WidgetPanelGridLogic.upsertItem(page, item)
    return effective.toMutableList().also { it[index] = updatedPage }
  }

  fun addAppToPage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    packageName: String,
    className: String,
    label: String,
    cloneUserId: Int = 0,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages)
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val spanX = 1
    val spanY = 1
    val slot = WidgetPanelGridLogic.findFirstFreeSlot(page, spanX, spanY)
      ?: return null
    val syntheticId = -kotlin.math.abs(
      (System.currentTimeMillis() xor packageName.hashCode().toLong() xor cloneUserId.toLong()).toInt(),
    )
    val item = WidgetPanelItem(
      appWidgetId = syntheticId,
      x = slot.first,
      y = slot.second,
      spanX = spanX,
      spanY = spanY,
      label = label,
      itemType = ITEM_TYPE_APP,
      packageName = packageName,
      className = className,
      cloneUserId = cloneUserId,
    )
    val updatedPage = WidgetPanelGridLogic.upsertItem(page, item)
    return effective.toMutableList().also { it[index] = updatedPage }
  }

  fun addShortcutToPage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    packageName: String,
    shortcutId: String,
    label: String,
    intentUri: String,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages)
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val spanX = 1
    val spanY = 1
    val slot = WidgetPanelGridLogic.findFirstFreeSlot(page, spanX, spanY)
      ?: return null
    val syntheticId = -kotlin.math.abs((System.currentTimeMillis() xor (packageName + shortcutId).hashCode().toLong()).toInt())
    val item = WidgetPanelItem(
      appWidgetId = syntheticId,
      x = slot.first,
      y = slot.second,
      spanX = spanX,
      spanY = spanY,
      label = label,
      itemType = ITEM_TYPE_SHORTCUT,
      packageName = packageName,
      shortcutId = shortcutId,
      // ⚠️ 落盘前转义：payload 自带的分隔符（\u001C/\u001D/\u001F）会把面板编码劈开。
      intentUri = WidgetPanelFieldCodec.encode(intentUri),
    )
    val updatedPage = WidgetPanelGridLogic.upsertItem(page, item)
    return effective.toMutableList().also { it[index] = updatedPage }
  }

  fun addActionToPage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    actionPayload: String,
    label: String,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages)
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val spanX = 1
    val spanY = 1
    val slot = WidgetPanelGridLogic.findFirstFreeSlot(page, spanX, spanY)
      ?: return null
    val syntheticId = -kotlin.math.abs((System.currentTimeMillis() xor actionPayload.hashCode().toLong()).toInt())
    val item = WidgetPanelItem(
      appWidgetId = syntheticId,
      x = slot.first,
      y = slot.second,
      spanX = spanX,
      spanY = spanY,
      label = label,
      itemType = ITEM_TYPE_ACTION,
      intentUri = actionPayload,
    )
    val updatedPage = WidgetPanelGridLogic.upsertItem(page, item)
    return effective.toMutableList().also { it[index] = updatedPage }
  }

  fun removeActionFromPage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    actionPayload: String,
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val updatedItems = page.items.filterNot {
      it.itemType == ITEM_TYPE_ACTION && it.intentUri == actionPayload
    }
    val updatedPage = page.copy(items = updatedItems)
    effective[index] = updatedPage.copy(rowCount = WidgetPanelGridLogic.computeContentRowCount(updatedPage))
    return effective
  }

  fun removeAppFromPage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    packageName: String,
    cloneUserId: Int = 0,
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val updatedItems = page.items.filterNot {
      it.itemType == ITEM_TYPE_APP && it.packageName == packageName && it.cloneUserId == cloneUserId
    }
    val updatedPage = page.copy(items = updatedItems)
    effective[index] = updatedPage.copy(rowCount = WidgetPanelGridLogic.computeContentRowCount(updatedPage))
    return effective
  }

  fun removeShortcutFromPage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    packageName: String,
    shortcutId: String,
    intentUri: String = "",
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    val updatedItems = page.items.filterNot {
      it.itemType == ITEM_TYPE_SHORTCUT &&
        it.packageName == packageName &&
        // 存进 item 的是转义过的值，比对前先解回原始 payload。
        (it.shortcutId == shortcutId ||
          (intentUri.isNotBlank() && WidgetPanelFieldCodec.decode(it.intentUri) == intentUri))
    }
    val updatedPage = page.copy(items = updatedItems)
    effective[index] = updatedPage.copy(rowCount = WidgetPanelGridLogic.computeContentRowCount(updatedPage))
    return effective
  }

  fun removeWidgetFromPage(
    context: Context,
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    appWidgetId: Int,
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    effective[index] = WidgetPanelGridLogic.removeItem(page, appWidgetId)
    if (appWidgetId > 0) {
      WidgetPopupHost.deleteAppWidgetId(context, appWidgetId)
    }
    return effective
  }

  fun replacePage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    page: WidgetPanelPage,
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    effective[index] = page.copy(rowCount = WidgetPanelGridLogic.computeContentRowCount(page))
    return effective
  }

  fun replaceItemOnPage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    item: WidgetPanelItem,
  ): List<WidgetPanelPage> {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    effective[index] = WidgetPanelGridLogic.upsertItem(effective[index], item)
    return effective
  }

  fun updateItemOnPage(
    pages: List<WidgetPanelPage>,
    pageIndex: Int,
    item: WidgetPanelItem,
  ): List<WidgetPanelPage>? {
    val effective = WidgetPanelDefaults.effectivePages(pages).toMutableList()
    val index = pageIndex.coerceIn(0, effective.lastIndex)
    val page = effective[index]
    if (!WidgetPanelGridLogic.isAreaFree(page, item.x, item.y, item.spanX, item.spanY, item.appWidgetId)) {
      return null
    }
    effective[index] = WidgetPanelGridLogic.upsertItem(page, item)
    return effective
  }
}
