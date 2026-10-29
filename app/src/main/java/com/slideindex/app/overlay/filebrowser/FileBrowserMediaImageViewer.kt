package com.slideindex.app.overlay.filebrowser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 媒体网格的图片查看器：content URI 直接解码，支持缩放/旋转/前后切换/分享。 */
@Composable
internal fun FileBrowserMediaImageViewer(
    item: FileBrowserMediaItem,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    var scale by remember(item.uri) { mutableStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }
    var rotation by remember(item.uri) { mutableStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset += panChange
    }
    LaunchedEffect(item.uri) {
        failed = false
        bitmap = withContext(Dispatchers.IO) { loadMediaBitmap(context, item) }
        failed = bitmap == null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Text(
                text = stringResource(R.string.file_browser_media_image_failed),
                color = MaterialTheme.colorScheme.error,
            )
            bitmap == null -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            else -> Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.file_browser_close),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onPrevious != null) {
                TextButton(onClick = onPrevious) {
                    Text(stringResource(R.string.file_browser_media_previous))
                }
            }
            if (onNext != null) {
                TextButton(onClick = onNext) {
                    Text(stringResource(R.string.file_browser_media_next))
                }
            }
            TextButton(onClick = {
                scale = 1f
                offset = Offset.Zero
            }) {
                Text("1:1")
            }
            TextButton(onClick = { rotation = (rotation + 90f) % 360f }) {
                Text(stringResource(R.string.file_browser_media_rotate))
            }
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.file_browser_share))
            }
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.file_browser_close))
            }
        }
    }
}

private fun loadMediaBitmap(
    context: android.content.Context,
    item: FileBrowserMediaItem,
): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(item.uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val maxEdge = 2_048
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return runCatching {
        resolver.openInputStream(item.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }.getOrNull()
}
