package com.slideindex.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.ui.compose.LocalAppDependencies
import com.slideindex.app.ui.theme.SlideIndexTheme
import com.slideindex.app.ui.WidgetPanelSubActionsResult
import com.slideindex.app.widget.WidgetPanelDefaults
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 「格子长按二级菜单」配置页（独立 Activity）。
 *
 * 由面板编辑模式点按格子 / 设置页拉起。面板是 `TYPE_APPLICATION_OVERLAY`，
 * 会让普通 Activity 压在下面，所以调用方必须先收起面板（与容器配置页同一处理）。
 */
@AndroidEntryPoint
class WidgetPanelSubActionsEditorActivity : ComponentActivity() {

    @Inject lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pageId = intent.getLongExtra(EXTRA_PAGE_ID, Long.MIN_VALUE)
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, Int.MIN_VALUE)
        val settings = deps.settingsRepository.readSnapshot()
        val pages = WidgetPanelDefaults.effectivePages(settings.widgetPanel.widgetPanelPages)
        val page = pages.firstOrNull { it.id == pageId }
        val item = page?.items?.firstOrNull { it.appWidgetId == widgetId }
        if (page == null || item == null) {
            finish()
            return
        }
        setContent {
            // 动作选择器要靠 LocalAppDependencies 列应用；缺了它一进选择器就崩。
            CompositionLocalProvider(LocalAppDependencies provides deps) {
                SlideIndexTheme {
                    // ⚠️ Miuix 玻璃层必须有 MiuixTopLayerRoot 宿主：
                    // 编辑器里的「容器图标 / 每项图标」选择器是 MiuixGlassDropdownMenu
                    //（玻璃下拉），没有 TopLayerRoot 时点击没有任何反应
                    //（上游 0.9.5 玻璃化后所有弹玻璃下拉的 Activity 都套了这层）。
                    com.slideindex.app.ui.miuix.MiuixTopLayerRoot {
                    WidgetPanelSubActionsEditorContent(
                        item = item,
                        onDismiss = { finish() },
                        onSave = { result ->
                            lifecycleScope.launch {
                                save(pageId, widgetId, result)
                                finish()
                            }
                        },
                    )
                    }
                }
            }
        }
    }

    private suspend fun save(pageId: Long, widgetId: Int, result: WidgetPanelSubActionsResult) {
        val settings = deps.settingsRepository.readSnapshot()
        val pages = WidgetPanelDefaults.effectivePages(settings.widgetPanel.widgetPanelPages)
        val updated = pages.map { page ->
            if (page.id != pageId) {
                page
            } else {
                page.copy(
                    items = page.items.map { item ->
                        if (item.appWidgetId == widgetId) {
                            item.copy(
                                subActions = result.subActions,
                                subMenu = result.subMenu,
                                iconKey = result.iconKey,
                                containerColorArgb = result.containerColorArgb,
                                iconColorArgb = result.iconColorArgb,
                                labelColorArgb = result.labelColorArgb,
                            )
                        } else {
                            item
                        }
                    },
                )
            }
        }
        deps.settingsRepository.setWidgetPanelPages(updated)
    }

    companion object {
        private const val EXTRA_PAGE_ID = "extra_page_id"
        private const val EXTRA_WIDGET_ID = "extra_widget_id"

        fun createIntent(context: Context, pageId: Long, widgetId: Int): Intent =
            Intent(context, WidgetPanelSubActionsEditorActivity::class.java)
                .putExtra(EXTRA_PAGE_ID, pageId)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
