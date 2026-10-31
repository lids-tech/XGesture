package com.slideindex.app.overlay.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.slideindex.app.search.ShareFileTargetResolver
import com.slideindex.app.search.ShareImageTarget
import com.slideindex.app.search.ShareImageTargetResolver
import com.slideindex.app.search.ShareTextTargetResolver

/**
 * 「分享出来的是什么」：内置分享面板要按内容形态决定两件事 ——
 * 列哪些目标（文本 / 图片 / 文件的系统分享目标集合本来就不一样），
 * 以及点中目标后发哪条 `ACTION_SEND`。
 *
 * 目标枚举一律复用已有的 `Share*TargetResolver`：它们已经处理过 MIME 组合、
 * 去重与拼音排序，面板只负责展示和直发。
 */
sealed interface ShareSheetPayload {

    /** 该形态能接收分享的目标列表（调用方必须在后台线程调用）。 */
    fun targets(context: Context): List<ShareImageTarget>

    /** 不带 component 的原始分享 Intent；点中目标后再补 `component` 直发。 */
    fun intent(): Intent

    data class Text(val text: String) : ShareSheetPayload {
        override fun targets(context: Context): List<ShareImageTarget> =
            ShareTextTargetResolver.listTargets(context)

        override fun intent(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    data class Image(
        val uri: Uri,
        val mimeType: String = "image/*",
    ) : ShareSheetPayload {
        override fun targets(context: Context): List<ShareImageTarget> =
            ShareImageTargetResolver.listTargets(context)

        override fun intent(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            // 部分目标 App 只认 clipData 里带的 URI 授权（旧 `getShareIntent` 同样这么做）。
            clipData = ClipData.newRawUri("share", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    data class Files(
        val uris: List<Uri>,
        val mimeType: String = "*/*",
    ) : ShareSheetPayload {
        override fun targets(context: Context): List<ShareImageTarget> =
            ShareFileTargetResolver.listTargets(context)

        override fun intent(): Intent =
            if (uris.size <= 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uris.firstOrNull())
                    uris.firstOrNull()?.let { clipData = ClipData.newRawUri("share", it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    buildClipData()?.takeIf { it.itemCount > 0 }?.let { clipData = it }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

        private fun buildClipData(): ClipData? {
            if (uris.isEmpty()) return null
            val first = ClipData.newRawUri("share", uris.first())
            uris.drop(1).forEach { first.addItem(ClipData.Item(it)) }
            return first
        }
    }
}
