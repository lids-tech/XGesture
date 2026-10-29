package com.slideindex.app.overlay.filebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import com.slideindex.app.R
import com.slideindex.app.overlay.LocalFrostedGlassBackdrop
import com.slideindex.app.ui.miuix.MiuixSearchField
import com.slideindex.app.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BreadcrumbBar
import top.yukonga.miuix.kmp.basic.BreadcrumbBarDefaults
import top.yukonga.miuix.kmp.basic.BreadcrumbItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文件浏览器面板内容（浮窗内）。
 *
 * 纯 UI：目录读取/权限判断全部在 [FileBrowserController] 里；这里只负责列表、面包屑、
 * 搜索与详情，并通过回调把“授权/关闭”交给宿主 [FileBrowserOverlayWindow]。
 */
@Composable
internal fun FileBrowserPanel(
    visible: Boolean,
    controller: FileBrowserController,
    onDismiss: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    val state = controller.state
    val internalRoot = controller.internalRoot
    val filtered = remember(state.entries, state.query, state.showHidden, state.sort) {
        FileBrowserSort.apply(
            entries = state.entries,
            order = state.sort,
            showHidden = state.showHidden,
            query = state.query,
        )
    }
    val listState = rememberLazyListState()
    var quickBarVisible by remember { mutableStateOf(true) }
    LaunchedEffect(state.activeTabId) {
        quickBarVisible = true
    }
    LaunchedEffect(listState, state.activeTabId) {
        var last = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { current ->
            when {
                current.first == 0 && current.second < 12 -> quickBarVisible = true
                current.first > last.first ||
                    (current.first == last.first && current.second > last.second + 18) ->
                    quickBarVisible = false
                current.first < last.first ||
                    (current.first == last.first && current.second < last.second - 18) ->
                    quickBarVisible = true
            }
            last = current
        }
    }
    var restoredTabId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.activeTabId, state.loading) {
        if (!state.loading && restoredTabId != state.activeTabId) {
            restoredTabId = state.activeTabId
            val (index, offset) = controller.scrollForActiveTab()
            runCatching { listState.scrollToItem(index, offset) }
        }
    }
    LaunchedEffect(listState, state.activeTabId) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            controller.saveScroll(index, offset)
        }
    }
    val levelBytes = remember(state.entries) {
        state.entries.asSequence()
            .filter { it.kind != FileEntryKind.DIRECTORY }
            .sumOf { it.sizeBytes }
    }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var copied by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var imagePreview by remember { mutableStateOf<FileBrowserEntry?>(null) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportFailed by remember { mutableStateOf(false) }
    var nameTarget by remember { mutableStateOf<FileBrowserNameTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<FileBrowserEntry?>(null) }
    var operationError by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()

    LaunchedEffect(operationError) {
        if (operationError) {
            delay(1_800L)
            operationError = false
        }
    }

    LaunchedEffect(state.details?.path) {
        exportBusy = false
        exportFailed = false
    }

    LaunchedEffect(state.path) {
        runCatching { listState.scrollToItem(0) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600L)
            copied = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + scaleIn(
            initialScale = 0.96f,
            animationSpec = tween(180),
        ),
        exit = fadeOut(tween(150)) + scaleOut(
            targetScale = 0.98f,
            animationSpec = tween(150),
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            val cardWidth = minOf(maxWidth * 0.94f, 560.dp)
            val cardHeight = minOf(maxHeight * 0.90f, 820.dp)
            FileBrowserCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(cardWidth)
                    .height(cardHeight),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FileBrowserHeader(
                            access = state.access,
                            onRefresh = controller::refresh,
                            onSearchClick = { searchExpanded = !searchExpanded },
                            onMenuClick = { showMenu = true },
                            onDismiss = onDismiss,
                        )
                        FileBrowserTabBar(
                            tabs = state.tabs,
                            activeTabId = state.activeTabId,
                            onSelect = controller::selectTab,
                            onClose = controller::closeTab,
                            onNew = controller::newTab,
                        )
                        FileBrowserBreadcrumb(
                            path = state.path,
                            internalRoot = internalRoot,
                            onNavigate = { target ->
                                focusManager.clearFocus()
                                controller.navigateTo(target)
                            },
                            onLongClick = { copyToClipboard(context, state.path) },
                        )
                        AnimatedVisibility(
                            visible = searchExpanded || state.query.isNotEmpty(),
                            enter = expandVertically(tween(160)) + fadeIn(tween(120)),
                            exit = shrinkVertically(tween(140)) + fadeOut(tween(100)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MiuixSearchField(
                                    query = state.query,
                                    onQueryChange = controller::setQuery,
                                    modifier = Modifier.weight(1f),
                                    hintResId = R.string.file_browser_search_hint,
                                )
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            searchExpanded = false
                                            controller.setQuery("")
                                            focusManager.clearFocus()
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(visible = quickBarVisible) {
                            FileBrowserQuickChips(
                                internalRoot = internalRoot,
                                onNavigate = { target ->
                                    focusManager.clearFocus()
                                    controller.navigateTo(target)
                                },
                            )
                        }
                        if (state.loading && state.entries.isNotEmpty()) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            )
                        }
                        FileBrowserLimitedBanner(
                            state = state,
                            internalRoot = internalRoot,
                            onRequestAllFiles = onRequestAllFiles,
                            onRequestShizuku = onRequestShizuku,
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            FileBrowserListContent(
                                state = state,
                                filtered = filtered,
                                internalRoot = internalRoot,
                                listState = listState,
                                onEntryClick = { entry ->
                                    focusManager.clearFocus()
                                    if (state.selectionMode) {
                                        controller.toggleSelection(entry.path)
                                    } else {
                                        when (entry.kind) {
                                            FileEntryKind.DIRECTORY,
                                            FileEntryKind.SYMLINK,
                                            -> controller.navigateTo(entry.path)

                                            FileEntryKind.FILE,
                                            FileEntryKind.OTHER,
                                            -> controller.showDetails(entry)
                                        }
                                    }
                                },
                                onEntryLongClick = { entry ->
                                    focusManager.clearFocus()
                                    controller.enterSelection(entry)
                                },
                                onDropEntry = { sourcePath, targetDir ->
                                    controller.dropEntryInto(sourcePath, targetDir, cut = true)
                                },
                                selectionMode = state.selectionMode,
                                selectedPaths = state.selectedPaths,
                                access = state.access,
                                onRetry = controller::refresh,
                                onRequestAllFiles = onRequestAllFiles,
                                onRequestShizuku = onRequestShizuku,
                            )
                        }
                        if (state.selectionMode) {
                            FileBrowserSelectionBar(
                                count = state.selectedPaths.size,
                                onCopy = { controller.copySelection(cut = false) },
                                onMove = { controller.copySelection(cut = true) },
                                onDelete = { showBatchDelete = true },
                                onDetails = if (state.selectedPaths.size == 1) {
                                    {
                                        state.entries
                                            .firstOrNull { it.path in state.selectedPaths }
                                            ?.let(controller::showDetails)
                                    }
                                } else {
                                    null
                                },
                                onCancel = controller::exitSelection,
                            )
                        }
                        if (!state.selectionMode && state.clipboard != null) {
                            FileBrowserClipboardBar(
                                clipboard = state.clipboard,
                                onPaste = controller::paste,
                                onCancel = controller::clearClipboard,
                            )
                        }
                        if (state.operationRunning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LinearProgressIndicator(
                                    progress = { state.operationProgress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(3.dp),
                                )
                                TextButton(onClick = controller::cancelOperation) {
                                    Text(stringResource(R.string.file_browser_cancel))
                                }
                            }
                        }
                        if (state.operationMessage != null) {
                            Text(
                                text = state.operationMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (state.lastTrash.isNotEmpty() && !state.operationRunning) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.file_browser_trash_count,
                                        state.lastTrash.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = controller::undoDelete) {
                                    Text(stringResource(R.string.file_browser_undo))
                                }
                                TextButton(onClick = controller::clearTrashUndo) {
                                    Text(stringResource(R.string.file_browser_cancel))
                                }
                            }
                        }
                        if (state.error == null && (filtered.isNotEmpty() || state.truncated)) {
                            FileBrowserFooter(
                                count = filtered.size,
                                sizeBytes = levelBytes,
                                directorySizeBytes = state.directorySizeBytes,
                                directorySizeLoading = state.directorySizeLoading,
                                directorySizeComputed = state.directorySizeComputed,
                                truncated = state.truncated,
                                onComputeSize = controller::computeDirectorySize,
                            )
                        }
                    }

                    if (copied) {
                        Text(
                            text = stringResource(R.string.file_browser_copied),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    val details = state.details
                    if (details != null) {
                        val openWithTitle = stringResource(R.string.file_browser_open_with)
                        val canDirectOpen = FileBrowserOpen.canOpen(details.path)
                        val canExport = !canDirectOpen &&
                            state.access?.mode == AccessMode.ROOT
                        val openWithLabel = when {
                            exportBusy -> stringResource(R.string.file_browser_exporting)
                            exportFailed -> stringResource(R.string.file_browser_export_failed)
                            canDirectOpen -> stringResource(R.string.file_browser_open_with)
                            canExport -> stringResource(R.string.file_browser_export_open)
                            else -> null
                        }
                        FileBrowserDetailsOverlay(
                            entry = details,
                            onCopy = {
                                copyToClipboard(context, details.path)
                                copied = true
                            },
                            onOpenWith = when {
                                canDirectOpen -> {
                                    {
                                        FileBrowserOpen.open(
                                            context = context,
                                            path = details.path,
                                            chooserTitle = openWithTitle,
                                        )
                                    }
                                }
                                canExport -> {
                                    {
                                        if (!exportBusy) {
                                            actionScope.launch {
                                                exportBusy = true
                                                exportFailed = false
                                                val exported = FileBrowserExport.exportToCache(
                                                    context = context,
                                                    sourcePath = details.path,
                                                )
                                                exportBusy = false
                                                if (exported != null) {
                                                    FileBrowserOpen.openFile(
                                                        context = context,
                                                        file = exported,
                                                        chooserTitle = openWithTitle,
                                                    )
                                                } else {
                                                    exportFailed = true
                                                }
                                            }
                                        }
                                    }
                                }
                                else -> null
                            },
                            onOpenImage = if (
                                FileKind.from(details.name, details.kind) == FileKind.IMAGE
                            ) {
                                { imagePreview = details }
                            } else {
                                null
                            },
                            openWithLabel = openWithLabel,
                            openWithEnabled = !exportBusy,
                            onRename = { nameTarget = FileBrowserNameTarget.Rename(details) },
                            onDelete = { deleteTarget = details },
                            onClose = controller::hideDetails,
                        )
                    }
                    if (showPathDialog) {
                        FileBrowserPathDialog(
                            initialPath = state.path,
                            onDismiss = { showPathDialog = false },
                            onNavigate = { target ->
                                showPathDialog = false
                                focusManager.clearFocus()
                                controller.navigateTo(target)
                            },
                        )
                    }
                    if (showSearchDialog) {
                        FileBrowserSearchDialog(
                            onDismiss = { showSearchDialog = false },
                            onSearch = { query ->
                                showSearchDialog = false
                                focusManager.clearFocus()
                                controller.searchRecursive(query)
                            },
                        )
                    }
                    state.searchResults?.let { results ->
                        FileBrowserSearchOverlay(
                            query = state.searchQuery.orEmpty(),
                            results = results,
                            loading = state.searchLoading,
                            onOpen = controller::openSearchResult,
                            onClose = controller::clearSearch,
                        )
                    }
                    if (showFavorites) {
                        FileBrowserFavoritesOverlay(
                            currentPath = state.path,
                            onNavigate = { path ->
                                showFavorites = false
                                controller.navigateTo(path)
                            },
                            onClose = { showFavorites = false },
                        )
                    }
                    if (showMenu) {
                        FileBrowserMenuOverlay(
                            showHidden = state.showHidden,
                            sort = state.sort,
                            pinned = FileBrowserPreferences.isPinned(state.path),
                            onSort = {
                                controller.setSort(it)
                                showMenu = false
                            },
                            onToggleHidden = {
                                controller.setShowHidden(!state.showHidden)
                                showMenu = false
                            },
                            onCopyPath = {
                                copyToClipboard(context, state.path)
                                copied = true
                                showMenu = false
                            },
                            onJumpPath = {
                                showMenu = false
                                showPathDialog = true
                            },
                            onNewFolder = {
                                showMenu = false
                                nameTarget = FileBrowserNameTarget.Create(state.path)
                            },
                            onRecursiveSearch = {
                                showMenu = false
                                showSearchDialog = true
                            },
                            onFavorites = {
                                showMenu = false
                                showFavorites = true
                            },
                            onTogglePin = {
                                FileBrowserPreferences.togglePin(state.path)
                                showMenu = false
                            },
                            onDismiss = { showMenu = false },
                        )
                    }
                    nameTarget?.let { target ->
                        val access = state.access
                        FileBrowserNameDialog(
                            initialName = when (target) {
                                is FileBrowserNameTarget.Create -> ""
                                is FileBrowserNameTarget.Rename -> target.entry.name
                            },
                            title = when (target) {
                                is FileBrowserNameTarget.Create ->
                                    stringResource(R.string.file_browser_new_folder)
                                is FileBrowserNameTarget.Rename ->
                                    stringResource(R.string.file_browser_rename)
                            },
                            confirmLabel = when (target) {
                                is FileBrowserNameTarget.Create ->
                                    stringResource(R.string.file_browser_create)
                                is FileBrowserNameTarget.Rename ->
                                    stringResource(R.string.file_browser_rename)
                            },
                            onDismiss = { nameTarget = null },
                            onConfirm = { name ->
                                nameTarget = null
                                if (access != null) {
                                    actionScope.launch {
                                        val ok = when (target) {
                                            is FileBrowserNameTarget.Create ->
                                                FileBrowserWrite.createDirectory(
                                                    contextPath = target.parent,
                                                    name = name,
                                                    access = access,
                                                )
                                            is FileBrowserNameTarget.Rename ->
                                                FileBrowserWrite.rename(
                                                    path = target.entry.path,
                                                    newName = name,
                                                    access = access,
                                                )
                                        }
                                        if (ok) {
                                            controller.hideDetails()
                                            controller.refresh()
                                        } else {
                                            operationError = true
                                        }
                                    }
                                }
                            },
                        )
                    }
                    deleteTarget?.let { target ->
                        FileBrowserDialog(
                            title = stringResource(R.string.file_browser_delete),
                            onDismiss = { deleteTarget = null },
                            confirmLabel = stringResource(R.string.file_browser_delete),
                            confirmColor = MaterialTheme.colorScheme.error,
                            onConfirm = {
                                deleteTarget = null
                                controller.deleteEntry(target)
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.file_browser_delete_message,
                                    target.name,
                                ),
                            )
                        }
                    }
                    if (showBatchDelete) {
                        FileBrowserDialog(
                            title = stringResource(R.string.file_browser_delete),
                            onDismiss = { showBatchDelete = false },
                            confirmLabel = stringResource(R.string.file_browser_delete),
                            confirmColor = MaterialTheme.colorScheme.error,
                            onConfirm = {
                                showBatchDelete = false
                                controller.deleteSelected()
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.file_browser_delete_selected_message,
                                    state.selectedPaths.size,
                                ),
                            )
                        }
                    }
                    if (state.pendingPaste != null) {
                        FileBrowserConflictDialog(
                            count = state.pendingConflicts,
                            onRename = { controller.resolvePaste(FileConflictPolicy.RENAME) },
                            onOverwrite = { controller.resolvePaste(FileConflictPolicy.OVERWRITE) },
                            onSkip = { controller.resolvePaste(FileConflictPolicy.SKIP) },
                            onCancel = controller::cancelPaste,
                        )
                    }
                    imagePreview?.let { entry ->
                        val images = filtered.filter {
                            FileKind.from(it.name, it.kind) == FileKind.IMAGE
                        }
                        val imageIndex = images.indexOfFirst { it.path == entry.path }
                        FileBrowserImageViewer(
                            entry = entry,
                            access = state.access,
                            onPrevious = if (imageIndex > 0) {
                                { imagePreview = images[imageIndex - 1] }
                            } else {
                                null
                            },
                            onNext = if (imageIndex >= 0 && imageIndex < images.lastIndex) {
                                { imagePreview = images[imageIndex + 1] }
                            } else {
                                null
                            },
                            onDetails = {
                                imagePreview = null
                                controller.showDetails(entry)
                            },
                            onEdit = { bitmap ->
                                val editorPath = com.slideindex.app.imageeditor.ImageEditorLaunchCache
                                    .put(context, bitmap)
                                com.slideindex.app.imageeditor.SlideIndexImageEditorActivity
                                    .launch(context, editorPath)
                            },
                            onClose = { imagePreview = null },
                        )
                    }
                    if (operationError) {
                        Text(
                            text = stringResource(R.string.file_browser_operation_failed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserMenuOverlay(
    showHidden: Boolean,
    sort: FileSortOrder,
    pinned: Boolean,
    onSort: (FileSortOrder) -> Unit,
    onToggleHidden: () -> Unit,
    onCopyPath: () -> Unit,
    onJumpPath: () -> Unit,
    onNewFolder: () -> Unit,
    onRecursiveSearch: () -> Unit,
    onFavorites: () -> Unit,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.file_browser_menu),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.file_browser_sort_name) + " / " +
                        stringResource(R.string.file_browser_sort_time) + " / " +
                        stringResource(R.string.file_browser_sort_size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                listOf(
                    Triple(R.string.file_browser_sort_name, FileSortOrder.NAME_ASC, FileSortOrder.NAME_DESC),
                    Triple(R.string.file_browser_sort_time, FileSortOrder.TIME_ASC, FileSortOrder.TIME_DESC),
                    Triple(R.string.file_browser_sort_size, FileSortOrder.SIZE_ASC, FileSortOrder.SIZE_DESC),
                ).forEach { (labelRes, asc, desc) ->
                    val active = sort == asc || sort == desc
                    val next = when {
                        !active -> if (labelRes == R.string.file_browser_sort_name) asc else desc
                        sort == asc -> desc
                        else -> asc
                    }
                    TextButton(
                        onClick = { onSort(next) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(labelRes) +
                                if (active) {
                                    if (sort == asc) " ↑" else " ↓"
                                } else {
                                    ""
                                },
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                HorizontalDivider()
                TextButton(onClick = onToggleHidden, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.file_browser_show_hidden) +
                            if (showHidden) " ✓" else "",
                    )
                }
                TextButton(onClick = onCopyPath, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_copy_current_path))
                }
                TextButton(onClick = onJumpPath, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_jump_path))
                }
                TextButton(onClick = onNewFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_new_folder))
                }
                TextButton(onClick = onRecursiveSearch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_recursive_search))
                }
                TextButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_favorites))
                }
                TextButton(onClick = onTogglePin, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (pinned) {
                                R.string.file_browser_unpin_current
                            } else {
                                R.string.file_browser_pin_current
                            },
                        ),
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.file_browser_cancel))
                }
            }
        }
    }
}

@Composable
private fun FileBrowserTabBar(
    tabs: List<FileBrowserTab>,
    activeTabId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(tabs, key = { it.id }) { tab ->
            val active = tab.id == activeTabId
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else Color.Transparent,
                    )
                    .padding(start = 9.dp, end = if (tabs.size > 1) 2.dp else 9.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tabTitle(tab.path),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 130.dp)
                        .clickable { onSelect(tab.id) },
                )
                if (tabs.size > 1) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClose(tab.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onNew),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun tabTitle(path: String): String = when (path) {
    "/" -> "/"
    "/storage/emulated/0" -> "内部存储"
    else -> path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}

@Composable
private fun FileBrowserHeader(
    access: FileBrowserAccessInfo?,
    onRefresh: () -> Unit,
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.file_browser_title),
                style = MiuixTheme.textStyles.title4,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FileBrowserAccessBadge(access)
        }
        IconButton(onClick = onSearchClick) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.file_browser_search_hint),
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.file_browser_refresh),
            )
        }
        IconButton(onClick = onMenuClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.file_browser_menu),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.file_browser_close),
            )
        }
    }
}

@Composable
private fun SortMenuItem(
    labelRes: Int,
    order: FileSortOrder,
    selected: FileSortOrder,
    ascending: Boolean,
    onSelect: (FileSortOrder) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes) + if (ascending) " ↑" else " ↓",
            )
        },
        onClick = { onSelect(order) },
        trailingIcon = {
            if (selected == order) {
                Icon(Icons.Filled.Check, contentDescription = null)
            }
        },
    )
}

@Composable
private fun FileBrowserAccessBadge(access: FileBrowserAccessInfo?) {
    val mode = access?.mode
    val textRes = when (mode) {
        null -> R.string.file_browser_access_detecting
        AccessMode.ROOT -> R.string.file_browser_access_root
        AccessMode.SHIZUKU -> R.string.file_browser_access_shizuku
        AccessMode.ALL_FILES -> R.string.file_browser_access_all_files
        AccessMode.LIMITED -> R.string.file_browser_access_limited
    }
    val color = when (mode) {
        AccessMode.ROOT, AccessMode.SHIZUKU -> MaterialTheme.colorScheme.primary
        AccessMode.ALL_FILES -> MaterialTheme.colorScheme.tertiary
        AccessMode.LIMITED -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileBrowserBreadcrumb(
    path: String,
    internalRoot: String,
    onNavigate: (String) -> Unit,
    onLongClick: () -> Unit,
) {
    val rootLabel = stringResource(R.string.file_browser_crumb_root)
    val internalLabel = stringResource(R.string.file_browser_crumb_internal)
    val normalizedInternal = remember(internalRoot) {
        FileBrowserPaths.normalize(internalRoot, internalRoot)
    }
    val items = remember(path, internalRoot, rootLabel, internalLabel) {
        FileBrowserPaths.crumbs(path, internalRoot).map { crumb ->
            val label = when (crumb.path) {
                "/" -> rootLabel
                normalizedInternal -> internalLabel
                else -> crumb.label
            }
            BreadcrumbItem(path = crumb.path, text = label)
        }
    }
    val scrollState = rememberScrollState()

    BreadcrumbBar(
        items = items,
        onItemClick = { index ->
            if (index != items.lastIndex) {
                onNavigate(items[index].path)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            ),
        highlightIndex = items.lastIndex,
        colors = BreadcrumbBarDefaults.breadcrumbBarColors(
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            highlightColor = MiuixTheme.colorScheme.primary,
            backgroundColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.08f),
            highlightBackgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.16f),
        ),
        itemMaxWidth = 120.dp,
        scrollState = scrollState,
    )
}

private data class QuickTarget(
    val labelRes: Int,
    val path: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBrowserQuickChips(
    internalRoot: String,
    onNavigate: (String) -> Unit,
) {
    val paths = FileBrowserPreferences.quickShortcuts(internalRoot)
    if (paths.isEmpty()) return
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val actionScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spacingPx = with(density) { 8.dp.toPx() }
    val itemBounds = remember { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var draggingPath by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var settlingTarget by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            paths.forEachIndexed { index, path ->
                val normalizedRoot = FileBrowserPaths.normalize(internalRoot, internalRoot)
                val label = when (path) {
                    "/" -> stringResource(R.string.file_browser_quick_root)
                    normalizedRoot -> stringResource(R.string.file_browser_quick_internal)
                    "$normalizedRoot/Android/data" -> stringResource(R.string.file_browser_quick_android_data)
                    "$normalizedRoot/Download" -> stringResource(R.string.file_browser_quick_download)
                    "$normalizedRoot/Android/obb" -> stringResource(R.string.file_browser_quick_obb)
                    "/data/data" -> stringResource(R.string.file_browser_quick_app_data)
                    else -> tabTitle(path)
                }
                val icon = when (path) {
                    "/" -> Icons.Outlined.Storage
                    normalizedRoot -> Icons.Outlined.PhoneAndroid
                    "$normalizedRoot/Android/data" -> Icons.Outlined.Android
                    "$normalizedRoot/Download" -> Icons.Outlined.Download
                    "/data/data" -> Icons.Outlined.Apps
                    else -> Icons.Outlined.Folder
                }
                val draggedWidth = itemBounds[draggingPath]?.width ?: 0f
                val fromIndex = draggingIndex
                val toIndex = dragTargetIndex
                val shiftTarget = when {
                    fromIndex == null || toIndex == null || index == fromIndex -> 0f
                    fromIndex < toIndex && index in (fromIndex + 1)..toIndex ->
                        -(draggedWidth + spacingPx)
                    fromIndex > toIndex && index in toIndex until fromIndex ->
                        draggedWidth + spacingPx
                    else -> 0f
                }
                val animatedShift by animateFloatAsState(
                    targetValue = shiftTarget,
                    animationSpec = if (draggingIndex == null) snap() else tween(120),
                    label = "quick-chip-shift",
                )
                val draggedVisual by animateFloatAsState(
                    targetValue = settlingTarget ?: dragOffset,
                    animationSpec = if (settlingTarget != null) tween(150) else snap(),
                    label = "quick-chip-drag",
                )
                Row(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            val position = coords.positionInParent()
                            val bounds = androidx.compose.ui.geometry.Rect(
                                left = position.x,
                                top = position.y,
                                right = position.x + coords.size.width,
                                bottom = position.y + coords.size.height,
                            )
                            if (itemBounds[path] != bounds) itemBounds[path] = bounds
                        }
                        .offset {
                            if (draggingPath == path) {
                                IntOffset(draggedVisual.roundToInt(), 0)
                            } else {
                                IntOffset(animatedShift.roundToInt(), 0)
                            }
                        }
                        .pointerInput(paths, path, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingPath = path
                                    draggingIndex = index
                                    dragTargetIndex = index
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                    val draggedCenter = itemBounds[path]?.center?.x ?: return@detectDragGesturesAfterLongPress
                                    val pointerX = draggedCenter + dragOffset
                                    val target = paths.indices.minByOrNull { candidate ->
                                        kotlin.math.abs(
                                            (itemBounds[paths[candidate]]?.center?.x ?: 0f) - pointerX,
                                        )
                                    }
                                    if (target != null && target != dragTargetIndex) {
                                        dragTargetIndex = target
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDragEnd = {
                                    val target = dragTargetIndex ?: index
                                    val draggedCenter = itemBounds[path]?.center?.x ?: 0f
                                    val targetCenter = itemBounds[paths.getOrNull(target)]?.center?.x ?: draggedCenter
                                    settlingTarget = targetCenter - draggedCenter
                                    actionScope.launch {
                                        delay(160)
                                        if (target != index) {
                                            FileBrowserPreferences.moveShortcut(
                                                index,
                                                target,
                                                internalRoot,
                                            )
                                        }
                                        draggingPath = null
                                        draggingIndex = null
                                        dragTargetIndex = null
                                        dragOffset = 0f
                                        settlingTarget = null
                                    }
                                },
                                onDragCancel = {
                                    draggingPath = null
                                    draggingIndex = null
                                    dragTargetIndex = null
                                    dragOffset = 0f
                                    settlingTarget = null
                                },
                            )
                        }
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
                        .clickable { onNavigate(path) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileBrowserFavoritesOverlay(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
) {
    val internalRoot = FileBrowserStorage.internalStoragePath()
    var favorites by remember {
        mutableStateOf(FileBrowserPreferences.quickShortcuts(internalRoot))
    }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var settlingTarget by remember { mutableStateOf<Float?>(null) }
    val actionScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 56.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    fun refresh() {
        favorites = FileBrowserPreferences.quickShortcuts(internalRoot)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.file_browser_favorites),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                if (favorites.isEmpty()) {
                    Text(
                        text = stringResource(R.string.file_browser_favorites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    favorites.forEachIndexed { index, path ->
                        val fromIndex = draggingIndex
                        val toIndex = dragTargetIndex
                        val shiftTarget = when {
                            fromIndex == null || toIndex == null || index == fromIndex -> 0f
                            fromIndex < toIndex && index in (fromIndex + 1)..toIndex -> -rowHeightPx
                            fromIndex > toIndex && index in toIndex until fromIndex -> rowHeightPx
                            else -> 0f
                        }
                        val animatedShift by animateFloatAsState(
                            targetValue = shiftTarget,
                            animationSpec = if (draggingIndex == null) snap() else tween(120),
                            label = "favorite-row-shift",
                        )
                        val draggedVisual by animateFloatAsState(
                            targetValue = settlingTarget ?: dragOffset,
                            animationSpec = if (settlingTarget != null) tween(150) else snap(),
                            label = "favorite-row-drag",
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset {
                                    if (draggingIndex == index) {
                                        IntOffset(0, draggedVisual.roundToInt())
                                    } else {
                                        IntOffset(0, animatedShift.roundToInt())
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .pointerInput(favorites, index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggingIndex = index
                                                dragTargetIndex = index
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.y
                                                val target = (index + (dragOffset / rowHeightPx).roundToInt())
                                                    .coerceIn(0, favorites.lastIndex)
                                                if (target != dragTargetIndex) {
                                                    dragTargetIndex = target
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            },
                                            onDragEnd = {
                                                val to = dragTargetIndex ?: index
                                                settlingTarget = (to - index) * rowHeightPx
                                                actionScope.launch {
                                                    delay(160)
                                                    if (to != index) {
                                                        FileBrowserPreferences.moveShortcut(
                                                            index,
                                                            to,
                                                            internalRoot,
                                                        )
                                                        refresh()
                                                    }
                                                    draggingIndex = null
                                                    dragTargetIndex = null
                                                    dragOffset = 0f
                                                    settlingTarget = null
                                                }
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                                dragTargetIndex = null
                                                dragOffset = 0f
                                                settlingTarget = null
                                            },
                                        )
                                    }
                                    .clickable { onNavigate(path) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tabTitle(path),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable {
                                        FileBrowserPreferences.removeShortcut(path)
                                        refresh()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        FileBrowserPreferences.togglePin(currentPath)
                        refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (FileBrowserPreferences.isPinned(currentPath)) {
                                R.string.file_browser_favorite_remove
                            } else {
                                R.string.file_browser_favorite_add
                            },
                        ),
                    )
                }
                if (FileBrowserPreferences.hiddenSystemShortcuts().isNotEmpty()) {
                    TextButton(
                        onClick = {
                            FileBrowserPreferences.resetHiddenShortcuts()
                            refresh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.file_browser_restore_shortcuts))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserConflictDialog(
    count: Int,
    onRename: () -> Unit,
    onOverwrite: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.file_browser_conflict_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.file_browser_conflict_message, count),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.file_browser_conflict_rename))
                    }
                    TextButton(onClick = onOverwrite, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.file_browser_conflict_overwrite),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.file_browser_conflict_skip))
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.file_browser_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserSelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDetails: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.file_browser_selected_count, count),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopy) {
            Text(stringResource(R.string.file_browser_copy))
        }
        TextButton(onClick = onMove) {
            Text(stringResource(R.string.file_browser_move))
        }
        if (onDetails != null) {
            TextButton(onClick = onDetails) {
                Text(stringResource(R.string.file_browser_selection_details))
            }
        }
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.file_browser_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.file_browser_cancel))
        }
    }
}

@Composable
private fun FileBrowserClipboardBar(
    clipboard: FileClipboard,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                if (clipboard.mode == FileTransferMode.MOVE) {
                    R.string.file_browser_clipboard_move
                } else {
                    R.string.file_browser_clipboard_copy
                },
                clipboard.paths.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onPaste) {
            Text(stringResource(R.string.file_browser_paste_here))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.file_browser_cancel))
        }
    }
}

@Composable
private fun FileBrowserFooter(
    count: Int,
    sizeBytes: Long,
    directorySizeBytes: Long?,
    directorySizeLoading: Boolean,
    directorySizeComputed: Boolean,
    truncated: Boolean,
    onComputeSize: () -> Unit,
) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val footerText = when {
            directorySizeLoading -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                stringResource(R.string.file_browser_size_calculating),
            )
            directorySizeComputed && directorySizeBytes != null -> stringResource(
                R.string.file_browser_footer_count_directory,
                count,
                FileBrowserFormat.size(directorySizeBytes),
            )
            directorySizeComputed -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                stringResource(R.string.file_browser_size_unavailable),
            )
            else -> stringResource(
                R.string.file_browser_footer_count_size,
                count,
                FileBrowserFormat.size(sizeBytes),
            )
        }
        Text(
            text = footerText,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!directorySizeLoading && !directorySizeComputed) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onComputeSize)
                    .padding(horizontal = 8.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.file_browser_compute_size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (truncated) {
            Text(
                text = stringResource(
                    R.string.file_browser_truncated,
                    FileBrowserShell.MAX_ENTRIES,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileBrowserCard(
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = LocalAppDarkTheme.current
    val corner = 28.dp
    val shape = RoundedCornerShape(corner)
    val fallback = MaterialTheme.colorScheme.surface.copy(
        alpha = if (isDark) 0.88f else 0.82f,
    )
    val borderColor = if (isDark) Color(0x28FFFFFF) else Color(0x18000000)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cornerPx = with(density) { corner.toPx() }
    val blurRadiusPx = with(density) { 57.dp.toPx().toInt() }

    Box(
        modifier = modifier
            .shadow(20.dp, shape, clip = false)
            .clip(shape)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(fallback),
        )
        LocalFrostedGlassBackdrop(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
            blurRadiusPx = blurRadiusPx,
            tintColor = if (isDark) 0x661C1C1E else 0x66F5F5F7,
            solidFallbackColor = fallback.toArgb(),
        )
        Box(modifier = Modifier.fillMaxSize(), content = content)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("path", text))
    }
}

@Composable
private fun FileBrowserDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                content()
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.file_browser_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onConfirm) {
                        Text(text = confirmLabel, color = confirmColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBrowserNameDialog(
    initialName: String,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialName) { mutableStateOf(initialName) }
    FileBrowserDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        onConfirm = {
            val name = value.trim()
            if (name.isNotEmpty()) onConfirm(name)
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.file_browser_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FileBrowserSearchDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    FileBrowserDialog(
        title = stringResource(R.string.file_browser_recursive_search),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.file_browser_go),
        onConfirm = {
            val query = value.trim()
            if (query.isNotEmpty()) onSearch(query)
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.file_browser_search_hint_recursive)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private sealed interface FileBrowserNameTarget {
    data class Create(val parent: String) : FileBrowserNameTarget
    data class Rename(val entry: FileBrowserEntry) : FileBrowserNameTarget
}

@Composable
private fun FileBrowserPathDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    var value by remember(initialPath) { mutableStateOf(initialPath) }
    FileBrowserDialog(
        title = stringResource(R.string.file_browser_jump_path),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.file_browser_go),
        onConfirm = {
            val target = value.trimEnd('/').ifEmpty { "/" }
            if (value.isNotEmpty()) onNavigate(target)
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            label = { Text(stringResource(R.string.file_browser_jump_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
