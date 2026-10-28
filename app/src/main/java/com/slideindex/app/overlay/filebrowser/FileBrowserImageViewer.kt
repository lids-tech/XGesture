package com.slideindex.app.overlay.filebrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 卡片内的简易图片查看器：支持缩放/拖动；Root 图片先导出到 cache。 */
@Composable
internal fun FileBrowserImageViewer(
    entry: FileBrowserEntry,
    access: FileBrowserAccessInfo?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(entry.path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(entry.path) { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset += panChange
    }
    LaunchedEffect(entry.path) {
        bitmap = withContext(Dispatchers.IO) {
            loadBitmap(context, entry, access)
        }
        failed = bitmap == null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(
                text = "无法打开图片",
                color = MaterialTheme.colorScheme.error,
            )
            bitmap == null -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            else -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .transformable(transformState),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
    }
}

private suspend fun loadBitmap(
    context: android.content.Context,
    entry: FileBrowserEntry,
    access: FileBrowserAccessInfo?,
): Bitmap? {
    val direct = File(entry.path)
    val file = if (direct.isFile && direct.canRead()) {
        direct
    } else if (access?.mode == AccessMode.ROOT) {
        FileBrowserExport.exportToCache(context, entry.path)
    } else {
        null
    } ?: return null
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}
