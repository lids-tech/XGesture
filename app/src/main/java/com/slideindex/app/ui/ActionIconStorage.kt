package com.slideindex.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.util.ImageDecodeGuard
import java.io.File
import java.io.FileOutputStream

/**
 * 动作自定义图标的**图片存储**。
 *
 * 为什么落盘成私有文件、而不是直接存相册 Uri：
 * 相册返回的 Uri 只有临时读权限，重启/清理后就失效（图标会变成空白）。
 * 这里在选图时立刻解码、缩放到 192px 并写进 `filesDir/action_icons/`，
 * 之后只读本地文件，与相册权限彻底解耦。
 *
 * 与 [ActionDisplayNameStore] 共用同一套动作身份键，所以"改动作内容 → 名字和图标一起失效"。
 */
object ActionIconStorage {

    private const val DIR_NAME = "action_icons"
    private const val MAX_SIZE_PX = 192

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 每次保存用**新文件名**（带时间戳），并删掉这个 key 的旧图。
     *
     * 为什么必须换名：图标预览、面板卡片、图标缓存全都按「文件路径 / iconKey」做键。
     * 之前固定文件名 → 重新选图后路径不变 → 缓存命中旧图，
     * 表现就是「编辑器里预览不更新、面板要重启 App 才变」。换名后路径变，各处自然刷新。
     */
    private fun rotatedFileName(baseName: String, extension: String = "png"): String =
        baseName.substringBeforeLast(".") + "-" +
            System.currentTimeMillis().toString(16) + "." + extension

    /** 删掉同一 key 的旧图标文件（老的固定名 + 历史轮换名）。 */
    private fun deleteOldFiles(folder: java.io.File, baseName: String) {
        val stem = baseName.substringBeforeLast(".")
        val prefix = stem + "-"
        runCatching {
            folder.listFiles()?.forEach { file ->
                if (file.isFile && (file.name == baseName || file.name.startsWith(prefix))) {
                    file.delete()
                }
            }
        }
    }
    private fun fileNameOf(context: Context, action: GestureAction): String =
        ActionDisplayNameStore.actionKey(action).hashCode().toUInt().toString(16) + ".png"

    /**
     * 从相册 Uri 存图标。
     *
     * @return 存好的本地文件路径（存进 [ActionDisplayNameStore.setIcon]）；失败返回 null。
     */
    fun saveFromUri(context: Context, action: GestureAction, uri: Uri): String? =
        saveUriToStorage(context, uri, fileNameOf(context, action))

    /**
     * 把相册 Uri 落成图标文件。
     *
     * ⚠️ 动图（GIF / 动画 WebP）必须**原样拷贝字节**：
     * 走 Bitmap 解码再压 PNG 只剩第一帧，动图就"不动"了。
     * 静图仍然缩放后压 PNG（体积小、尺寸统一）。
     */
    private fun saveUriToStorage(context: Context, uri: Uri, baseName: String): String? {
        val folder = dir(context)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val animatedExtension = when (mime) {
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/apng" -> "apng"
            else -> null
        }
        if (animatedExtension != null) {
            deleteOldFiles(folder, baseName)
            val target = File(folder, rotatedFileName(baseName, animatedExtension))
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            return if (copied) target.absolutePath else null
        }
        val bitmap = ImageDecodeGuard.decodeUri(
            context = context,
            uri = uri,
            maxSidePx = MAX_SIZE_PX,
        ) ?: return null
        val scaled = scaleDown(bitmap)
        deleteOldFiles(folder, baseName)
        val target = File(folder, rotatedFileName(baseName))
        val ok = runCatching {
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.isSuccess
        if (scaled !== bitmap) scaled.recycle()
        if (bitmap !== scaled) bitmap.recycle()
        return if (ok) target.absolutePath else null
    }

    /** 读回自定义图标；没设过 / 文件丢了返回 null（调用方回退到动作自带图标）。 */
    fun load(context: Context, action: GestureAction, iconKey: String?): Bitmap? {
        val path = iconKey?.takeIf { it.isNotBlank() } ?: return null
        if (!File(path).exists()) return null
        return ImageDecodeGuard.decodeFile(path, MAX_SIZE_PX)
    }

    /** 删掉这个动作的自定义图标文件（恢复默认时调用）。 */
    fun delete(context: Context, action: GestureAction) {
        deleteOldFiles(dir(context), fileNameOf(context, action))
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= MAX_SIZE_PX) return bitmap
        val ratio = MAX_SIZE_PX.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 全局图标：按任意 key（如 `global:action:OPEN_INDEX`）存图片。 */
    fun saveFromUriForGlobalKey(context: Context, key: String, uri: android.net.Uri): String? =
        saveUriToStorage(context, uri, globalFileName(key))

    /** 全局图标：按任意 key 删除图片文件。 */
    fun deleteForGlobalKey(context: Context, key: String) {
        deleteOldFiles(dir(context), globalFileName(key))
    }

    private fun globalFileName(key: String): String =
        key.hashCode().toUInt().toString(16) + ".png"

    /** 全局图标：把 Bitmap（如应用图标）按任意 key 落盘。 */
    fun saveBitmapForGlobalKey(context: Context, key: String, bitmap: Bitmap): String? {
        val baseName = globalFileName(key)
        deleteOldFiles(dir(context), baseName)
        val target = File(dir(context), rotatedFileName(baseName))
        val ok = runCatching {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.isSuccess
        return if (ok) target.absolutePath else null
    }
}
