package com.slideindex.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.slideindex.app.R
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.SettingsScreenScaffold
import com.slideindex.app.ui.settings.components.SettingSwitchRow
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import com.slideindex.app.ui.settings.components.settingsLazySmallTitle
import com.slideindex.app.ui.settings.components.settingsLazyTipCard

/**
 * 「分享面板」设置页：只有一枚总开关。
 *
 * 为什么不挂在取词页下面：内置面板同时服务取词面板、文件浏览器、拖放流转，
 * 放在任何单一浮窗的设置里都会让另外两个入口找不到它。
 */
@Composable
fun ShareSheetSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    // Scaffold 的 content 是 LazyListScope 扩展（非 @Composable），资源必须在这里先取出来。
    val pageTitle = stringResource(R.string.share_sheet_settings_title)
    val pageSubtitle = stringResource(R.string.share_sheet_settings_subtitle)
    val tipText = stringResource(R.string.share_sheet_settings_tip)
    val sectionTitle = stringResource(R.string.share_sheet_settings_section)
    val toggleTitle = stringResource(R.string.share_sheet_toggle_title)
    val toggleDesc = stringResource(R.string.share_sheet_toggle_desc)
    val pinTip = stringResource(R.string.share_sheet_settings_pin_tip)

    SettingsScreenScaffold(
        title = pageTitle,
        subtitle = pageSubtitle,
        onBack = onBack,
    ) {
        settingsLazyTipCard(
            key = "share-sheet-tip",
            text = tipText,
        )
        settingsLazySmallTitle(
            key = "share-sheet-section",
            title = sectionTitle,
        )
        groupedCardItems(
            keyPrefix = "share-sheet",
            items = listOf(
                settingsCardScopeItem("share-sheet-enabled") {
                    SettingSwitchRow(
                        title = toggleTitle,
                        subtitle = toggleDesc,
                        icon = { label ->
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        checked = settings.shareSheetEnabled,
                        enabled = true,
                        onCheckedChange = onEnabledChange,
                    )
                },
            ),
        )
        settingsLazyTipCard(
            key = "share-sheet-pin-tip",
            text = pinTip,
        )
    }
}
