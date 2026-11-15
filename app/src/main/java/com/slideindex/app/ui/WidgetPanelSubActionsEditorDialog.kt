package com.slideindex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.overlay.volumepanel.ExpandPanelSlotPicker
import com.slideindex.app.ui.viewmodel.ExtensionSettingsViewModel
import com.slideindex.app.widget.MAX_SUB_ACTIONS
import com.slideindex.app.widget.WidgetPanelItem
import com.slideindex.app.widget.WidgetPanelSubActions

/**
 * 「格子的长按二级菜单」编辑器。
 *
 * 列出该格子已配置的动作（可删），「添加动作」进入与音量面板同款的选择器
 * （[ExpandPanelSlotPicker]，自带应用 / 快捷方式 / 动作 / 轮盘页数据源）。
 * 没配任何动作的格子长按不弹菜单（松手 = 小窗打开 / 原点击行为）。
 */
@Composable
fun WidgetPanelSubActionsEditorDialog(
    item: WidgetPanelItem,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val viewModel: ExtensionSettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var drafts by remember(item) { mutableStateOf(item.subActions) }
    var picking by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf(listOf<com.slideindex.app.data.AppInfo>()) }
    var appsLoaded by remember { mutableStateOf(false) }

    val appRepository = com.slideindex.app.ui.compose.rememberAppRepository()
    LaunchedEffect(Unit) {
        allApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.slideindex.app.overlay.CloneAppsIndex.enrich(appRepository.loadApps(force = true))
        }
        appsLoaded = true
    }

    fun labelFor(action: GestureAction): String = when (action) {
        is GestureAction.LaunchApp ->
            allApps.firstOrNull { it.packageName == action.packageName }?.label.orEmpty()
        is GestureAction.LaunchShortcut -> action.label
        is GestureAction.OpenLink -> action.label
        else -> ""
    }.takeIf { it.isNotBlank() } ?: WidgetPanelSubActions.fallbackLabel(action)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("二级菜单", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "长按“${item.label.ifBlank { item.packageName }}”时弹出的动作",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row {
                            TextButton(onClick = { onSave(drafts); onDismiss() }) { Text("保存") }
                            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                        }
                    }
                    if (drafts.isEmpty()) {
                        Text(
                            "还没配置动作：长按该格子将不弹菜单，松手直接按原方式打开。",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(drafts.size) { index ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = WidgetPanelSubActions.parse(listOf(drafts[index]))
                                            .firstOrNull()?.label ?: "(无效动作)",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    IconButton(
                                        onClick = {
                                            drafts = drafts.filterIndexed { i, _ -> i != index }
                                        },
                                    ) { Icon(Icons.Default.Delete, null) }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { picking = true },
                        enabled = drafts.size < MAX_SUB_ACTIONS && appsLoaded,
                        modifier = Modifier.padding(top = 8.dp).heightIn(min = 40.dp),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text("添加动作")
                    }
                }

                if (picking) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                ExpandPanelSlotPicker(
                                    allApps = allApps,
                                    activityShortcuts = settings.activityShortcuts,
                                    current = null,
                                    wheels = settings.launcher.quickWheels,
                                    panels = settings.quickLauncherPanels,
                                    widgetPanelPages = com.slideindex.app.widget.WidgetPanelDefaults.effectivePages(settings.widgetPanelPages),
                                    onSelect = { action ->
                                        picking = false
                                        if (drafts.size < MAX_SUB_ACTIONS) {
                                            drafts = drafts +
                                                WidgetPanelSubActions.encode(labelFor(action), action)
                                        }
                                    },
                                    onCancel = { picking = false },
                                    onClearSlot = null,
                                    imeBottom = 0.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
