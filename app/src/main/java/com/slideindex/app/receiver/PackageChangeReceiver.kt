package com.slideindex.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.overlay.share.ShareSheetTargetCache
import com.slideindex.app.service.OverlayService
import com.slideindex.app.widget.WidgetCatalog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PackageChangeReceiver : BroadcastReceiver() {
    @Inject lateinit var deps: AppDependencies

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
            -> Unit
            else -> return
        }
        deps.appRepository.invalidate()
        WidgetCatalog.invalidate()
        // 分享目标列表的进程级缓存：装/卸/更新/组件变更都会改变"谁能接收分享"，
        // 这里直接清掉，下次打开分享面板重新枚举（同一进程，函数调用即可）。
        ShareSheetTargetCache.onPackagesChanged()
        deps.appRepository.requestRefresh("package:${intent.action}")
        context.sendBroadcast(
            Intent(OverlayService.ACTION_RELOAD_APPS).setPackage(context.packageName),
        )
    }
}
