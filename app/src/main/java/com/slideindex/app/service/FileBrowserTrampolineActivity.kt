package com.slideindex.app.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.R
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.overlay.filebrowser.FileBrowserOverlayWindow
import com.slideindex.app.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 外部入口 / 桌面快捷方式：打开文件浏览器，可带 path。
 *
 * adb:
 *   am start -a com.slideindex.app.action.OPEN_FILE_BROWSER -n com.slideindex.app/.service.FileBrowserTrampolineActivity
 *   am start -a com.slideindex.app.action.OPEN_FILE_BROWSER -n ... --es path /data/data
 */
@AndroidEntryPoint
class FileBrowserTrampolineActivity : ComponentActivity() {

    @Inject
    lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_PATH)
            ?.takeIf { it.startsWith("/") }
        lifecycleScope.launch {
            if (!PermissionHelper.isAccessibilityServiceEnabledForOverlays(this@FileBrowserTrampolineActivity)) {
                toast(R.string.gesture_action_stash_panel_permission)
                startActivity(PermissionHelper.accessibilitySettingsIntent())
                finishTransparent()
                return@launch
            }
            val settings = deps.settingsRepository.settings.first()
            if (!settings.serviceEnabled) {
                toast(R.string.gesture_action_stash_panel_permission)
                finishTransparent()
                return@launch
            }
            OverlayServiceLifecycle.syncFromSettings(
                this@FileBrowserTrampolineActivity,
                deps.settingsRepository,
            )
            val shown = runCatching {
                FileBrowserOverlayWindow.show(this@FileBrowserTrampolineActivity, settings, path)
            }.getOrDefault(false)
            if (!shown) toast(R.string.shortcut_panel_open_failed)
            finishTransparent()
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun finishTransparent() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_OPEN_FILE_BROWSER = "com.slideindex.app.action.OPEN_FILE_BROWSER"
        const val EXTRA_PATH = "path"

        fun intent(context: Context, path: String? = null): Intent =
            Intent(context, FileBrowserTrampolineActivity::class.java).apply {
                action = ACTION_OPEN_FILE_BROWSER
                path?.let { putExtra(EXTRA_PATH, it) }
            }
    }
}
