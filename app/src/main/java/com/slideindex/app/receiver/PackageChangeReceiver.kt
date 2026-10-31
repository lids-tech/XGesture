package com.slideindex.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED,
            -> Unit
            else -> return
        }
        // 分享目标列表的进程级缓存：装/卸/更新/组件变更都会改变"谁能接收分享"。
        // 放在最前面，且单独兜异常 —— 后面 AppRepository 的刷新出错也不能让分享面板继续拿旧列表。
        runCatching { ShareSheetTargetCache.onPackagesChanged() }
            .onFailure { Log.w(TAG, "share target cache invalidate failed", it) }
        Log.i(TAG, "package change: $action pkg=${intent.data?.schemeSpecificPart}")

        runCatching {
            deps.appRepository.invalidate()
            WidgetCatalog.invalidate()
            deps.appRepository.requestRefresh("package:$action")
        }.onFailure { Log.w(TAG, "app repository invalidate failed", it) }

        runCatching {
            context.sendBroadcast(
                Intent(OverlayService.ACTION_RELOAD_APPS).setPackage(context.packageName),
            )
        }.onFailure { Log.w(TAG, "reload apps broadcast failed", it) }
    }

    private companion object {
        const val TAG = "PackageChange"
    }
}
