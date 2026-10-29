package com.slideindex.app.overlay.filebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 多选分享：直接文件用 FileProvider，Root 文件先导出到 cache。 */
internal suspend fun shareFileBrowserPaths(
    context: Context,
    paths: List<String>,
    access: FileBrowserAccessInfo?,
): Int {
    val uris = withContext(Dispatchers.IO) {
        paths.mapNotNull { path ->
            runCatching {
                val direct = File(path)
                val file = if (direct.isFile && direct.canRead()) {
                    direct
                } else if (access?.mode == AccessMode.ROOT) {
                    FileBrowserExport.exportToCache(context, path)
                } else {
                    null
                } ?: return@mapNotNull null
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        }
    }
    if (uris.isEmpty()) return 0
    withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享文件").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        FileBrowserOverlayWindow.dismiss()
        context.startActivity(chooser)
    }
    return uris.size
}
