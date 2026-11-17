package com.slideindex.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.SettingsRepository
import com.slideindex.app.ui.feedback.UserMessageBus
import com.slideindex.app.widget.WidgetPanelDefaults
import com.slideindex.app.widget.WidgetPanelGridLogic
import com.slideindex.app.widget.WidgetPanelPage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 小组件面板设置页的状态。 */
data class WidgetPanelUiState(
    val pages: List<WidgetPanelPage> = emptyList(),
    val selectedPageIndex: Int = 0,
    val blurEnabled: Boolean = false,
    val blurRadiusDp: Int = 16,
    val closeOnClickMode: Int = 0,
    /** 图标下方是否显示名称；关闭后只画图标（名称仍用于无障碍朗读）。 */
    val showLabels: Boolean = true,
    /** 二级菜单全局布局（列数 / 行数 / 左右偏移 / 上下偏移）。 */
    val submenuColumns: Int = 3,
    val submenuRows: Int = 2,
    val submenuOffsetXDp: Int = 0,
    val submenuOffsetYDp: Int = 0,
    /** 呼出入场动画（关掉 = 与上游一致）。 */
    val openAnimation: Boolean = true,
    val openAnimationMs: Int = 240,
    val openAnimationTranslateDp: Int = 12,
    val isGridInteractionActive: Boolean = false,
    val isLoading: Boolean = false,
) {
    val currentPage: WidgetPanelPage
        get() = pages.getOrElse(selectedPageIndex) { WidgetPanelDefaults.defaultPage }
}

@HiltViewModel
class WidgetPanelEditorViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    userMessageBus: UserMessageBus,
    @ApplicationContext context: Context,
) : SettingsViewModel(settingsRepository, userMessageBus, context) {

    private val _selectedPageIndex = MutableStateFlow(0)
    private val _isGridInteractionActive = MutableStateFlow(false)

    val uiState: StateFlow<WidgetPanelUiState> = combine(
        this.settings,
        _selectedPageIndex,
        _isGridInteractionActive,
    ) { currentSettings, selectedIndex, gridActive ->
        val pages = WidgetPanelDefaults.effectivePages(currentSettings.widgetPanelPages)
            .map { WidgetPanelGridLogic.fitPageToGrid(it) }
        val safeIndex = selectedIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        WidgetPanelUiState(
            pages = pages,
            selectedPageIndex = safeIndex,
            blurEnabled = currentSettings.widgetPanelBlurEnabled,
            blurRadiusDp = currentSettings.widgetPanelBlurRadiusDp,
            closeOnClickMode = currentSettings.widgetPanelCloseOnClickMode,
            showLabels = currentSettings.widgetPanelShowLabels,
            submenuColumns = currentSettings.widgetSubmenuColumns,
            submenuRows = currentSettings.widgetSubmenuRows,
            submenuOffsetXDp = currentSettings.widgetSubmenuOffsetXDp,
            submenuOffsetYDp = currentSettings.widgetSubmenuOffsetYDp,
            openAnimation = currentSettings.widgetPanelOpenAnimation,
            openAnimationMs = currentSettings.widgetPanelOpenAnimationMs,
            openAnimationTranslateDp = currentSettings.widgetPanelOpenAnimationTranslateDp,
            isGridInteractionActive = gridActive,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WidgetPanelUiState(isLoading = true),
    )

    fun selectPage(index: Int) {
        _selectedPageIndex.value = index
    }

    fun setGridInteractionActive(active: Boolean) {
        _isGridInteractionActive.value = active
    }

    fun setPages(pages: List<WidgetPanelPage>) {
        launchSettingsWrite {
            settingsRepository.setWidgetPanelPages(pages)
        }
    }

    fun setBlurEnabled(enabled: Boolean) {
        launchSettingsWrite {
            settingsRepository.setWidgetPanelBlurEnabled(enabled)
        }
    }

    fun setBlurRadius(radiusDp: Int) {
        launchSettingsWrite {
            settingsRepository.setWidgetPanelBlurRadiusDp(radiusDp)
        }
    }

    fun setCloseOnClickMode(mode: Int) {
        launchSettingsWrite {
            settingsRepository.setWidgetPanelCloseOnClickMode(mode)
        }
    }

    fun setShowLabels(show: Boolean) {
        launchSettingsWrite {
            settingsRepository.setWidgetPanelShowLabels(show)
        }
    }

    fun setSubmenuLayout(columns: Int, rows: Int, offsetXDp: Int, offsetYDp: Int) {
        launchSettingsWrite {
            settingsRepository.setWidgetSubmenuLayout(columns, rows, offsetXDp, offsetYDp)
        }
    }

    fun setOpenAnimation(enabled: Boolean) {
        launchSettingsWrite { settingsRepository.setWidgetPanelOpenAnimation(enabled) }
    }

    fun setOpenAnimationMs(durationMs: Int) {
        launchSettingsWrite { settingsRepository.setWidgetPanelOpenAnimationMs(durationMs) }
    }

    fun setOpenAnimationTranslateDp(translateDp: Int) {
        launchSettingsWrite { settingsRepository.setWidgetPanelOpenAnimationTranslateDp(translateDp) }
    }
}
