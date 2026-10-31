package com.slideindex.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.iconpack.IconPackDrawableLoader
import com.slideindex.app.iconpack.IconPackInfo
import com.slideindex.app.iconpack.IconPackRepository
import com.slideindex.app.iconpack.IconThemeStore
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扩展页里的「图标包」：选一个包，**全局**套用到所有应用图标。
 *
 * 语义：
 * - 选包 = 主题生效；「不使用图标包」= 关闭；
 * - 图标库里**单独设过图标**的应用永远优先，不受这里影响；
 * - 未收录的应用回退系统图标。
 */
@Composable
fun IconThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var packs by remember { mutableStateOf<List<IconPackInfo>?>(null) }
    var current by remember { mutableStateOf(IconThemeStore.pack(context)) }

    LaunchedEffect(Unit) {
        packs = withContext(Dispatchers.IO) { IconPackRepository.scan(context) }
    }

    SettingsScreenScaffold(
        title = stringResource(R.string.icon_theme_title),
        subtitle = stringResource(R.string.icon_theme_subtitle),
        onBack = onBack,
    ) {
        item(key = "icon-theme-none") {
            IconThemeRow(
                label = stringResource(R.string.icon_theme_none),
                summary = stringResource(R.string.icon_theme_none_desc),
                selected = current == null,
                onClick = {
                    IconThemeStore.setPack(context, null)
                    current = null
                },
            )
        }

        when (val list = packs) {
            null -> item(key = "icon-theme-loading") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.loading),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            else -> if (list.isEmpty()) {
                item(key = "icon-theme-empty") {
                    Text(
                        text = stringResource(R.string.icon_theme_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                items(items = list, key = { it.packageName }) { info ->
                    IconThemeRow(
                        label = info.label,
                        summary = info.packageName,
                        icon = info.icon,
                        selected = current == info.packageName,
                        onClick = {
                            IconThemeStore.setPack(context, info.packageName, info.label)
                            current = info.packageName
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IconThemeRow(
    label: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: android.graphics.drawable.Drawable? = null,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, label) {
        value = withContext(Dispatchers.IO) {
            icon?.let { IconPackDrawableLoader.toBitmap(it, 96) }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val shown = bitmap
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (selected) {
            Text(
                text = stringResource(R.string.icon_theme_active),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
