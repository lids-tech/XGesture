package com.slideindex.app.clipboardoverlay

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import com.slideindex.app.R
import com.slideindex.app.imageeditor.ImageEditorLaunchCache
import com.slideindex.app.imageeditor.SlideIndexImageEditorActivity
import com.slideindex.app.overlay.share.ShareSheetPayload
import com.slideindex.app.util.resolveActivityCompat

internal object ClipboardOverlayIntents {
    private const val REMOTE_COPY_ACTION = "android.intent.action.REMOTE_COPY"
    private const val GMS_PACKAGE = "com.google.android.gms"
    private const val LEGACY_REMOTE_COPY_COMPONENT =
        "com.google.android.gms/.nearby.sharing.ShareSheetActivity"
    private const val EXTRA_EDIT_SOURCE = "edit_source"
    private const val EDIT_SOURCE_CLIPBOARD = "clipboard"

    fun getTextEditorIntent(context: Context, text: CharSequence?): Intent {
        return Intent(context, ClipboardOverlayEditTextActivity::class.java).apply {
            putExtra(ClipboardOverlayEditTextActivity.EXTRA_TEXT, text?.toString().orEmpty())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }

    /**
     * 把剪贴板条目转成内置分享面板的载荷；条目为空/空白时返回 null（调用方据此直接收起浮窗）。
     *
     * 以前这里直接返回一条 `createChooser` Intent，从剪贴板浮窗发起分享时会被自家浮层盖住。
     */
    fun sharePayload(context: Context, clipData: ClipData): ShareSheetPayload? {
        val item = if (clipData.itemCount > 0) clipData.getItemAt(0) else return null
        val uri = item.uri
        if (uri != null) {
            val mime = clipData.description.getMimeType(0) ?: "image/*"
            return ShareSheetPayload.Image(uri = uri, mimeType = mime)
        }
        val text = item.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) return null
        return ShareSheetPayload.Text(text)
    }

    fun getImageEditIntent(uri: Uri, context: Context): Intent {
        val editorPackage = context.getString(R.string.clipboard_overlay_screenshot_editor)
        val editIntent = Intent(Intent.ACTION_EDIT)
        if (!TextUtils.isEmpty(editorPackage)) {
            editIntent.component = ComponentName.unflattenFromString(editorPackage)
        }
        editIntent.setDataAndType(uri, "image/*")
        editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        editIntent.putExtra(EXTRA_EDIT_SOURCE, EDIT_SOURCE_CLIPBOARD)
        return editIntent
    }

    fun launchBuiltinImageEditor(context: Context, bitmap: android.graphics.Bitmap) {
        val editorPath = ImageEditorLaunchCache.put(context, bitmap)
        SlideIndexImageEditorActivity.launch(context, editorPath)
    }

    fun resolveRemoteCopyIntent(clipData: ClipData, context: Context): Intent? {
        val pm = context.packageManager
        val preferred = context.getString(R.string.clipboard_overlay_remote_copy_package)
        val candidates = buildList {
            add(remoteCopyIntent(clipData, packageName = GMS_PACKAGE))
            for (flat in listOf(preferred, LEGACY_REMOTE_COPY_COMPONENT).distinct()) {
                if (flat.isEmpty()) continue
                val component = ComponentName.unflattenFromString(flat) ?: continue
                add(remoteCopyIntent(clipData, component = component))
            }
        }
        return candidates.firstOrNull { pm.resolveActivityCompat(it) != null }
    }

    private fun remoteCopyIntent(
        clipData: ClipData,
        packageName: String? = null,
        component: ComponentName? = null,
    ): Intent {
        return Intent(REMOTE_COPY_ACTION).apply {
            this.clipData = clipData
            if (component != null) {
                this.component = component
            } else if (!packageName.isNullOrEmpty()) {
                setPackage(packageName)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }
}
