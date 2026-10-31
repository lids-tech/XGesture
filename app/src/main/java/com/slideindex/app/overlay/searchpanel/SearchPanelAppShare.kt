package com.slideindex.app.overlay.searchpanel

import android.content.Context
import androidx.core.content.FileProvider
import com.slideindex.app.BuildConfig
import com.slideindex.app.overlay.share.ShareSheetLauncher
import com.slideindex.app.overlay.share.ShareSheetPayload
import java.io.File

internal object SearchPanelAppShare {
    fun shareApk(context: Context, packageName: String, dismissSource: (() -> Unit)? = null): Boolean {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        val apkFile = File(appInfo.sourceDir)
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apkFile,
        )
        return ShareSheetLauncher.share(
            context = context,
            payload = ShareSheetPayload.Files(
                uris = listOf(uri),
                mimeType = "application/vnd.android.package-archive",
            ),
            dismissSource = dismissSource,
        )
    }
}
