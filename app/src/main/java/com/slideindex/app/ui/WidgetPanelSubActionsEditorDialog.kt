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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slideindex.app.gesture.ActionPickerCatalogPolicy
import com.slideindex.app.gesture.GestureAction
import com.slideindex.app.gesture.GestureTriggerType
import com.slideindex.app.gesture.SlotPickerKind
import com.slideindex.app.ui.picker.ActivityShortcutPickActivityScreen
import com.slideindex.app.ui.picker.ActivityShortcutPickAppScreen
import com.slideindex.app.ui.picker.MyShortcutsFolderScreen
import com.slideindex.app.ui.picker.PresetShortcutsFolderScreen
import com.slideindex.app.ui.viewmodel.ExtensionSettingsViewModel
import com.slideindex.app.widget.MAX_SUB_ACTIONS
import com.slideindex.app.widget.WidgetPanelItem
import com.slideindex.app.widget.WidgetPanelSubAction
import com.slideindex.app.widget.WidgetPanelSubActions
import com.slideindex.app.widget.WidgetPanelSubMenuConfig

/** 配置页返回给调用方的结果：二级菜单项 + 布局 + 一级格子自己的图标。 */
data class WidgetPanelSubActionsResult(
    val subActions: List<String>,
    val subMenu: WidgetPanelSubMenuConfig,
    val iconKey: String,
)

/**
 * 格子「长按二级菜单」配置页。
 *
 * 结构照快捷轮盘的容器编辑：列表里是已加的动作（图标 + 名称 + 删除），
 * 「添加动作」进 `GestureActionPickerScreen`（动作 / 应用程序 / 快捷方式三页，
 * 快捷方式页带「创建快捷方式」），二级页全部接齐。
 *
 * 在此基础上多了这块面板特有的三件事：
 * - 每项可改**名称**、可换**图标**（复用 `ActionIconPickerHost`：图标库 / 文字 / 应用 / 图片）；
 * - 菜单**行列数 / 上下左右偏移 / 是否显示名称**可调；
 * - 一级格子本身也能换图标（同样走 `ActionIconPickerHost`）。
 */
@Composable
fun WidgetPanelSubActionsEditorDialog(
    item: WidgetPanelItem,
    onDismiss: () -> Unit,
    onSave: (WidgetPanelSubActionsResult) -> Unit,
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
    onSave: (WidgetPanelSubActionsResult) -> Unit,
) {
    val viewModel: ExtensionSettingsViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var drafts by remember(item) { mutableStateOf(item.subActions) }
    var config by remember(item) { mutableStateOf(item.subMenu.normalized()) }
    var cellIconKey by remember(item) { mutableStateOf(item.iconKey) }
    var page by remember { mutableStateOf<SubActionPage>(SubActionPage.List) }

    fun addAction(action: GestureAction) {
        if (drafts.size >= MAX_SUB_ACTIONS) return
        drafts = drafts + WidgetPanelSubActions.encode(action, customName = "", iconKey = "")
        page = SubActionPage.List
    }

    /** 替换第 [index] 项（改名称 / 改图标都走这里）。 */
    fun replace(index: Int, entry: String) {
        drafts = drafts.toMutableList().also { it[index] = entry }
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
                        TextButton(onClick = {
                            onSave(WidgetPanelSubActionsResult(drafts, config, cellIconKey))
                            onDismiss()
                        }) { Text("保存") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ── 一级格子自己的图标 ──
                    item(key = "cell-icon") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("格子图标", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            ActionIconPickerHost(
                                action = item.toGestureAction(),
                                iconKey = cellIconKey.takeIf { it.isNotBlank() },
                                sizeDp = 40,
                                storageKey = "widget-panel-cell:${item.appWidgetId}",
                                onIconChange = { key -> cellIconKey = key.orEmpty() },
                                onReset = { cellIconKey = "" },
                            )
                        }
                    }

                    // ── 布局：行列 / 偏移 / 显示名称 ──
                    item(key = "layout") {
                        Text("布局", style = MaterialTheme.typography.titleSmall)
                        StepperRow("列数", config.columns, WidgetPanelSubMenuConfig.MIN_COLUMNS..WidgetPanelSubMenuConfig.MAX_COLUMNS) {
                            config = config.copy(columns = it)
                        }
                        StepperRow("行数", config.rows, WidgetPanelSubMenuConfig.MIN_ROWS..WidgetPanelSubMenuConfig.MAX_ROWS) {
                            config = config.copy(rows = it)
                        }
                        StepperRow("左右偏移(dp)", config.offsetXDp, -240..240, step = 8) {
                            config = config.copy(offsetXDp = it)
                        }
                        StepperRow("上下偏移(dp)", config.offsetYDp, -240..240, step = 8) {
                            config = config.copy(offsetYDp = it)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("显示名称", modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.showLabels,
                                onCheckedChange = { config = config.copy(showLabels = it) },
                            )
                        }
                    }

                    item(key = "items-header") {
                        Text(
                            if (drafts.isEmpty()) "还没有动作：长按不弹菜单" else "菜单项",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    itemsIndexed(drafts) { index, entry ->
                        val parsed = WidgetPanelSubActions.parse(listOf(entry)).firstOrNull()
                        if (parsed == null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("(无效动作)", modifier = Modifier.weight(1f))
                                IconButton(onClick = { replace(index, entry); drafts = drafts.filterIndexed { i, _ -> i != index } }) {
                                    Icon(Icons.Default.Delete, null)
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ActionIconPickerHost(
                                    action = parsed.action,
                                    iconKey = parsed.iconKey.takeIf { it.isNotBlank() },
                                    sizeDp = 40,
                                    storageKey = "widget-panel-sub:${item.appWidgetId}:$index",
                                    onIconChange = { key ->
                                        replace(
                                            index,
                                            WidgetPanelSubActions.encode(
                                                parsed.action,
                                                parsed.customName,
                                                key.orEmpty(),
                                            ),
                                        )
                                    },
                                    onReset = {
                                        replace(
                                            index,
                                            WidgetPanelSubActions.encode(parsed.action, parsed.customName, ""),
                                        )
                                    },
                                )
                                OutlinedTextField(
                                    value = parsed.customName,
                                    onValueChange = { name ->
                                        replace(
                                            index,
                                            WidgetPanelSubActions.encode(parsed.action, name, parsed.iconKey),
                                        )
                                    },
                                    placeholder = {
                                        Text(WidgetPanelSubActions.displayName(context, parsed.copy(customName = "")))
                                    },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
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

/** 「- 值 +」步进行；[step] 为每次增减量（偏移用 8dp）。 */
@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range.first, range.last)) }) {
            Text("−")
        }
        Text(
            text = value.toString(),
            modifier = Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range.first, range.last)) }) {
            Text("+")
        }
    }
}

/** 把面板格子还原成一个"代表动作"，只用于图标选择页展示与默认图标解析。 */
private fun WidgetPanelItem.toGestureAction(): GestureAction {
    return when (itemType) {
        com.slideindex.app.widget.ITEM_TYPE_ACTION ->
            com.slideindex.app.launcher.QuickLauncherItemCodec.parseActionPayload(intentUri)
                ?: GestureAction.None
        com.slideindex.app.widget.ITEM_TYPE_SHORTCUT -> shortcutRepresentativeAction()
        else -> GestureAction.LaunchApp(packageName = packageName, cloneUserId = cloneUserId)
    }
}

/** 面板格子里的「快捷方式」可能是动态 / 组件 / intent 形态，只取一个代表用于图标选择。 */
private fun WidgetPanelItem.shortcutRepresentativeAction(): GestureAction {
    val decoded = com.slideindex.app.gesture.GestureShortcutPayload.decode(intentUri)
        ?: return GestureAction.None
    return when (decoded) {
        is com.slideindex.app.gesture.GestureShortcutPayload.Decoded.Dynamic ->
            GestureAction.LaunchShortcut.dynamic(decoded.packageName, decoded.shortcutId, decoded.label)
        is com.slideindex.app.gesture.GestureShortcutPayload.Decoded.Component ->
            GestureAction.LaunchShortcut.component(decoded.componentFlat, decoded.label)
        is com.slideindex.app.gesture.GestureShortcutPayload.Decoded.IntentShortcut,
        is com.slideindex.app.gesture.GestureShortcutPayload.Decoded.IntentsShortcut,
        ->
            GestureAction.LaunchShortcut.component(
                com.slideindex.app.gesture.GestureShortcutPayload.encodeComponent(
                    "$packageName/$className",
                    label,
                ),
                label,
            )
    }
}
