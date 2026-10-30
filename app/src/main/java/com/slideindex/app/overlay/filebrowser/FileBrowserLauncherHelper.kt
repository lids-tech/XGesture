package com.slideindex.app.overlay.filebrowser

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import android.widget.Toast
import com.slideindex.app.R
import com.slideindex.app.service.FileBrowserTrampolineActivity

/** 文件浏览器桌面入口：使用 pinned shortcut，而不是 activity-alias，长按才能“移除”。 */
internal object FileBrowserLauncherHelper {
    private const val TAG = "FileBrowserLauncherHelper"
    private const val SHORTCUT_ID = "file_browser_panel"

    fun requestPinShortcut(context: Context): Boolean {
        val appContext = context.applicationContext
        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java) ?: return false
        if (!shortcutManager.isRequestPinShortcutSupported) return false

        val launchIntent = FileBrowserTrampolineActivity.intent(appContext).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val label = appContext.getString(R.string.file_browser_title)
        val shortcut = ShortcutInfo.Builder(appContext, SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(appContext, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        return runCatching {
            shortcutManager.requestPinShortcut(shortcut, null)
        }.onFailure { error ->
            Log.e(TAG, "requestPinShortcut failed", error)
        }.getOrDefault(false)
    }

    fun showPinFailedToast(context: Context) {
        Toast.makeText(
            context,
            R.string.file_browser_add_to_home_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }
}
