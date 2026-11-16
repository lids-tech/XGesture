package com.slideindex.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.gesture.ActionPickerCatalogPolicy
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.SlotPickerKind
import com.slideindex.app.ui.viewmodel.ExtensionSettingsViewModel
import com.slideindex.app.ui.picker.ActivityShortcutPickActivityScreen
import com.slideindex.app.ui.picker.ActivityShortcutPickAppScreen
import com.slideindex.app.ui.picker.MyShortcutsFolderScreen
import com.slideindex.app.ui.picker.PresetShortcutsFolderScreen
import com.slideindex.app.widget.MAX_SUB_ACTIONS
import com.slideindex.app.widget.WidgetPanelItem
import com.slideindex.app.widget.WidgetPanelSubActions

/**
 * 格子「长按二级菜单」配置页。
 *
 * 交互与**快捷轮盘的容器编辑完全一致**：列表里是已加的动作（图标 + 名称 + 删除），
 * 「添加」进入 `GestureActionPickerScreen`（动作 / 应用程序 / 快捷方式三页，
 * 快捷方式页带「我的直达 / 预设快捷方式库 / 创建快捷方式」），
 * 二级页（我的快捷方式 / 预设 / 选应用 / 选 Activity / 执行命令 / 模拟按键）也和各处一致。
 */
@Composable
fun WidgetPanelSubActionsEditorDialog(
    item: WidgetPanelItem,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            WidgetPanelSubActionsEditorContent(item = item, onDismiss = onDismiss, onSave = onSave)
        }
    }
}

private sealed interface SubActionPage {
    data object List : SubActionPage
    data object ActionPick : SubActionPage
    data object MyShortcuts : SubActionPage
    data object PresetShortcuts : SubActionPage
    data object PickApp : SubActionPage
    data class PickActivity(val packageName: String) : SubActionPage
    data class ShellCommand(val initialCommand: String) : SubActionPage
    data class SimulateKeyEvent(val action: GestureAction.SimulateKeyEvent) : SubActionPage
}

@Composable
fun WidgetPanelSubActionsEditorContent(
    item: WidgetPanelItem,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val viewModel: ExtensionSettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var drafts by remember(item) { mutableStateOf(item.subActions) }
    var page by remember { mutableStateOf<SubActionPage>(SubActionPage.List) }

    fun addAction(action: GestureAction) {
        if (drafts.size >= MAX_SUB_ACTIONS) return
        drafts = drafts + WidgetPanelSubActions.encode(
            WidgetPanelSubActions.displayName(context, action),
            action,
        )
        page = SubActionPage.List
    }

    when (val current = page) {
        SubActionPage.List -> {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("二级菜单", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "长按「${item.label.ifBlank { item.packageName }}」时弹出",
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
                        "还没有动作。长按该格子不会弹菜单，松手按原方式打开。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(drafts) { index, entry ->
                            val parsed = WidgetPanelSubActions.parse(listOf(entry), context).firstOrNull()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (parsed != null) {
                                    AndroidView(
                                        factory = { ctx ->
                                            android.widget.ImageView(ctx).apply {
                                                val size = (32 * ctx.resources.displayMetrics.density).toInt()
                                                WidgetPanelSubActions
                                                    .icon(ctx, parsed.action, size)
                                                    ?.let { setImageBitmap(it) }
                                            }
                                        },
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                }
                                Text(
                                    text = parsed?.label ?: "(无效动作)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                                ) { Icon(Icons.Default.Delete, null) }
                            }
                        }
                    }
                }

                Button(
                    onClick = { page = SubActionPage.ActionPick },
                    enabled = drafts.size < MAX_SUB_ACTIONS,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("添加动作")
                }
            }
        }

        SubActionPage.ActionPick -> GestureActionPickerScreen(
            trigger = GestureTriggerType.SHORT_SWIPE_IN,
            current = GestureAction.None,
            catalogPolicy = ActionPickerCatalogPolicy.Slot(SlotPickerKind.OverlayTap),
            onDismiss = { page = SubActionPage.List },
            onSelect = { action -> addAction(action) },
            onOpenMyShortcuts = { page = SubActionPage.MyShortcuts },
            onOpenPresetShortcuts = { page = SubActionPage.PresetShortcuts },
            onOpenPickApp = { page = SubActionPage.PickApp },
            onOpenExecuteShellCommand = { command -> page = SubActionPage.ShellCommand(command) },
            onOpenSimulateKeyEvent = { event -> page = SubActionPage.SimulateKeyEvent(event) },
        )

        SubActionPage.MyShortcuts -> MyShortcutsFolderScreen(
            activityShortcuts = settings.activityShortcuts,
            onBack = { page = SubActionPage.ActionPick },
            onBrowseNewShortcut = { page = SubActionPage.PickApp },
            onSelectRadio = { action -> addAction(action) },
        )

        SubActionPage.PresetShortcuts -> PresetShortcutsFolderScreen(
            onBack = { page = SubActionPage.ActionPick },
            onSelectRadio = { action -> addAction(action) },
        )

        SubActionPage.PickApp -> ActivityShortcutPickAppScreen(
            onBack = { page = SubActionPage.ActionPick },
            onSelectApp = { app -> page = SubActionPage.PickActivity(app.packageName) },
        )

        is SubActionPage.PickActivity -> ActivityShortcutPickActivityScreen(
            packageName = current.packageName,
            onBack = { page = SubActionPage.PickApp },
            onSelectActivity = { activity ->
                val component = "${activity.packageName}/${activity.className}"
                addAction(GestureAction.LaunchShortcut.component(component, activity.label))
            },
        )

        is SubActionPage.ShellCommand -> GestureExecuteShellCommandScreen(
            initialCommand = current.initialCommand,
            shellCommands = settings.shellCommands,
            overlayMode = true,
            onBack = { page = SubActionPage.ActionPick },
            onConfirm = { command -> addAction(GestureAction.ExecuteShellCommand(command)) },
        )

        is SubActionPage.SimulateKeyEvent -> GestureSimulateKeyEventScreen(
            initialAction = current.action,
            onBack = { page = SubActionPage.ActionPick },
            onConfirm = { action -> addAction(action) },
        )
    }
}
