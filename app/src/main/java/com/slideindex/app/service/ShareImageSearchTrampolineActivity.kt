package com.slideindex.app.service

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.R
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.overlay.searchpanel.SearchPanelOverlayWindow
import com.slideindex.app.overlay.searchpanel.SearchPanelSessionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享菜单里的「分享到 XGesture 搜索」入口。
 *
 * 收到的图片不直接弹以图搜图，而是先塞进搜索面板的待消费槽位，再把面板拉起来 ——
 * 由面板决定用哪个图搜引擎，和拖拽进来的图片走同一条路。
 */
@AndroidEntryPoint
class ShareImageSearchTrampolineActivity : ComponentActivity() {

    @Inject
    lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleHandleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scheduleHandleShareIntent(intent)
    }

    private fun scheduleHandleShareIntent(shareIntent: Intent?) {
        if (
            shareIntent?.action != Intent.ACTION_SEND &&
            shareIntent?.action != Intent.ACTION_SEND_MULTIPLE
        ) {
            finish()
            return
        }
        window.decorView.post {
            lifecycleScope.launch {
                handleShareIntent(shareIntent)
                finishTransparent()
            }
        }
    }

    private suspend fun handleShareIntent(shareIntent: Intent) {
        if (!SlideIndexAccessibilityService.isConnected()) {
            Toast.makeText(this, R.string.share_image_ocr_service_required, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = ShareImageOcrCoordinator.resolveImageUri(shareIntent)
        if (uri == null) {
            Toast.makeText(this, R.string.share_image_ocr_image_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 分享授权只在本次启动有效，先在自己进程里解成位图再交给浮层，别把 Uri 留到后面用。
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { ShareImageOcrCoordinator.decodeShareImage(this@ShareImageSearchTrampolineActivity, uri) }
                .getOrNull()
        }
        if (bitmap == null) {
            Toast.makeText(this, R.string.share_image_ocr_image_load_failed, Toast.LENGTH_SHORT).show()
            return
        }

        SearchPanelSessionState.pushImage(bitmap)
        OverlayServiceLifecycle.syncFromSettings(this@ShareImageSearchTrampolineActivity, deps.settingsRepository)

        repeat(SHOW_RETRY_ATTEMPTS) { attempt ->
            if (SearchPanelOverlayWindow.show(this@ShareImageSearchTrampolineActivity)) {
                return
            }
            if (attempt < SHOW_RETRY_ATTEMPTS - 1) {
                delay(SHOW_RETRY_DELAY_MS)
            }
        }
        // 面板没起来就别把图留在槽位里，下次展开会莫名其妙多出一张。
        SearchPanelSessionState.pushImage(null)
        Toast.makeText(this, R.string.shortcut_panel_open_failed, Toast.LENGTH_SHORT).show()
    }

    private fun finishTransparent() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val SHOW_RETRY_ATTEMPTS = 3
        const val SHOW_RETRY_DELAY_MS = 120L
    }
}
