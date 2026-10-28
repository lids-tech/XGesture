package com.slideindex.app.overlay.filebrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileBrowserUiState(
    val path: String,
    val entries: List<FileBrowserEntry> = emptyList(),
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: FileSortOrder = FileSortOrder.NAME_ASC,
    val loading: Boolean = false,
    val error: FileBrowserError? = null,
    val details: FileBrowserEntry? = null,
    val access: FileBrowserAccessInfo? = null,
    val limited: Boolean = false,
    val truncated: Boolean = false,
    val directorySizeBytes: Long? = null,
    val directorySizeLoading: Boolean = false,
    val directorySizeComputed: Boolean = false,
    val searchResults: List<FileBrowserEntry>? = null,
    val searchQuery: String? = null,
    val searchLoading: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    val clipboard: FileClipboard? = null,
    val operationRunning: Boolean = false,
    val operationProgress: Float = 0f,
    val operationMessage: String? = null,
    val pendingPaste: FileClipboard? = null,
    val pendingConflicts: Int = 0,
    val lastTrash: List<TrashedItem> = emptyList(),
    val tabs: List<FileBrowserTab> = emptyList(),
    val activeTabId: Long = 0L,
)

data class FileClipboard(
    val mode: FileTransferMode,
    val paths: List<String>,
)

data class FileBrowserTab(
    val id: Long,
    val path: String,
)

/**
 * 文件浏览器状态机。
 *
 * 所有状态写入都发生在其 [scope]（Main.immediate），目录读取在 IO；切换目录时取消旧任务，
 * 避免“先点 A 再点 B，A 的结果回来覆盖 B”。Session 跨窗口保留，所以关闭面板不会重置路径。
 */
internal class FileBrowserController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val dataSource = FileBrowserDataSource(context)
    private val appContext = context.applicationContext
    private var loadJob: Job? = null
    private var sizeJob: Job? = null
    private var searchJob: Job? = null
    private var operationJob: Job? = null
    private var nextTabId: Long = 1L

    val internalRoot: String get() = dataSource.internalRoot

    var state by mutableStateOf(
        FileBrowserUiState(path = dataSource.initialPath()),
    )
        private set

    init {
        FileBrowserPreferences.attach(context) { prefs ->
            scope.launch {
                if (state.path != dataSource.initialPath()) return@launch
                val last = prefs.lastPath ?: return@launch
                val target = FileBrowserPaths.normalize(last, internalRoot)
                if (target == state.path) return@launch
                state = state.copy(
                    path = target,
                    showHidden = prefs.showHidden,
                    sort = prefs.sortOrderOrDefault(),
                    tabs = tabsWithActivePath(target),
                )
                reload()
            }
        }
        val prefs = FileBrowserPreferences.current
        val initialPath = FileBrowserPaths.normalize(
            prefs.lastPath ?: dataSource.initialPath(),
            dataSource.internalRoot,
        )
        state = FileBrowserUiState(
            path = initialPath,
            showHidden = prefs.showHidden,
            sort = prefs.sortOrderOrDefault(),
            tabs = listOf(FileBrowserTab(id = 0L, path = initialPath)),
            activeTabId = 0L,
        )
    }

    /** 面板重新可见：清掉搜索词/详情，应用排序/隐藏偏好，然后刷新。 */
    fun onShow() {
        val prefs = FileBrowserPreferences.current
        val target = if (state.path == dataSource.initialPath() && !prefs.lastPath.isNullOrBlank()) {
            FileBrowserPaths.normalize(prefs.lastPath, internalRoot)
        } else {
            state.path
        }
        state = state.copy(
            path = target,
            query = "",
            details = null,
            showHidden = prefs.showHidden,
            sort = prefs.sortOrderOrDefault(),
            loading = true,
            error = null,
            directorySizeBytes = null,
            directorySizeLoading = false,
            directorySizeComputed = false,
            tabs = tabsWithActivePath(target),
        )
        reload(resolveAccess = true)
    }

    fun navigateTo(path: String) {
        val normalized = FileBrowserPaths.normalize(path, internalRoot)
        if (normalized == state.path && state.error == null) return
        searchJob?.cancel()
        searchJob = null
        state = state.copy(
            path = normalized,
            entries = emptyList(),
            query = "",
            details = null,
            loading = true,
            error = null,
            limited = false,
            truncated = false,
            directorySizeBytes = null,
            directorySizeLoading = false,
            directorySizeComputed = false,
            searchResults = null,
            searchQuery = null,
            searchLoading = false,
            selectionMode = false,
            selectedPaths = emptySet(),
            tabs = tabsWithActivePath(normalized),
        )
        FileBrowserPreferences.rememberLastPath(normalized)
        reload(resolveAccess = false)
    }

    fun goParent() {
        FileBrowserPaths.parent(state.path, internalRoot)?.let(::navigateTo)
    }

    fun selectTab(id: Long) {
        if (id == state.activeTabId) return
        val tabs = state.tabs.map {
            if (it.id == state.activeTabId) it.copy(path = state.path) else it
        }
        val target = tabs.firstOrNull { it.id == id } ?: return
        state = state.copy(tabs = tabs, activeTabId = id)
        navigateTo(target.path)
    }

    fun newTab() {
        val tabs = state.tabs.map {
            if (it.id == state.activeTabId) it.copy(path = state.path) else it
        }
        val id = nextTabId++
        state = state.copy(
            tabs = tabs + FileBrowserTab(id = id, path = state.path),
            activeTabId = id,
            selectionMode = false,
            selectedPaths = emptySet(),
            details = null,
        )
    }

    fun closeTab(id: Long) {
        if (state.tabs.size <= 1) return
        val index = state.tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val remaining = state.tabs.filterNot { it.id == id }
        if (id != state.activeTabId) {
            state = state.copy(tabs = remaining)
            return
        }
        val next = remaining[(index - 1).coerceAtLeast(0)]
        state = state.copy(tabs = remaining, activeTabId = next.id)
        navigateTo(next.path)
    }

    private fun tabsWithActivePath(path: String): List<FileBrowserTab> =
        state.tabs.map {
            if (it.id == state.activeTabId) it.copy(path = path) else it
        }

    fun refresh() {
        reload(resolveAccess = true)
    }

    /** 授权返回后调用：重新探测访问能力并重载当前目录。 */
    fun refreshAccessAndReload() {
        reload(resolveAccess = true)
    }

    fun setQuery(query: String) {
        state = state.copy(query = query)
    }

    fun setShowHidden(show: Boolean) {
        state = state.copy(showHidden = show)
        FileBrowserPreferences.setShowHidden(show)
    }

    fun setSort(order: FileSortOrder) {
        state = state.copy(sort = order)
        FileBrowserPreferences.setSort(order)
    }

    fun showDetails(entry: FileBrowserEntry) {
        state = state.copy(details = entry)
    }

    fun hideDetails() {
        state = state.copy(details = null)
    }

    fun enterSelection(entry: FileBrowserEntry) {
        state = state.copy(
            selectionMode = true,
            selectedPaths = setOf(entry.path),
            details = null,
        )
    }

    fun toggleSelection(path: String) {
        val selected = if (path in state.selectedPaths) {
            state.selectedPaths - path
        } else {
            state.selectedPaths + path
        }
        state = state.copy(
            selectionMode = selected.isNotEmpty() || state.selectionMode,
            selectedPaths = selected,
        )
    }

    fun exitSelection() {
        state = state.copy(selectionMode = false, selectedPaths = emptySet())
    }

    fun copySelection(cut: Boolean) {
        if (state.selectedPaths.isEmpty()) return
        state = state.copy(
            clipboard = FileClipboard(
                mode = if (cut) FileTransferMode.MOVE else FileTransferMode.COPY,
                paths = state.selectedPaths.toList(),
            ),
            selectionMode = false,
            selectedPaths = emptySet(),
        )
    }

    fun clearClipboard() {
        state = state.copy(clipboard = null)
    }

    fun paste() {
        val clipboard = state.clipboard ?: return
        if (state.operationRunning) return
        val conflicts = FileBrowserOperations.conflicts(clipboard.paths, state.path)
        if (conflicts > 0) {
            state = state.copy(
                pendingPaste = clipboard,
                pendingConflicts = conflicts,
                selectionMode = false,
                selectedPaths = emptySet(),
            )
            return
        }
        executePaste(clipboard, FileConflictPolicy.RENAME)
    }

    fun resolvePaste(policy: FileConflictPolicy) {
        val clipboard = state.pendingPaste ?: return
        state = state.copy(pendingPaste = null, pendingConflicts = 0)
        executePaste(clipboard, policy)
    }

    fun cancelPaste() {
        state = state.copy(pendingPaste = null, pendingConflicts = 0)
    }

    private fun executePaste(clipboard: FileClipboard, policy: FileConflictPolicy) {
        val access = state.access ?: return
        if (state.operationRunning) return
        val target = state.path
        state = state.copy(
            operationRunning = true,
            operationProgress = 0f,
            operationMessage = null,
            selectionMode = false,
            selectedPaths = emptySet(),
        )
        operationJob = scope.launch {
            val report = FileBrowserOperations.transfer(
                paths = clipboard.paths,
                targetDir = target,
                mode = clipboard.mode,
                access = access,
                policy = policy,
            ) { done, total ->
                state = state.copy(
                    operationProgress = if (total == 0) 0f else done.toFloat() / total,
                )
            }
            if (!isActive) return@launch
            state = state.copy(
                operationRunning = false,
                operationProgress = 0f,
                operationMessage = when {
                    report.failures.isNotEmpty() -> "有 ${report.failures.size} 项失败"
                    report.skipped > 0 -> "已跳过 ${report.skipped} 项"
                    else -> null
                },
                clipboard = if (report.failures.isEmpty()) null else clipboard,
            )
            refresh()
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        state = state.copy(
            operationRunning = false,
            operationProgress = 0f,
            operationMessage = "已取消",
        )
    }

    fun deleteEntry(entry: FileBrowserEntry) {
        val access = state.access ?: return
        if (state.operationRunning) return
        state = state.copy(
            operationRunning = true,
            operationProgress = 0f,
            operationMessage = null,
        )
        operationJob = scope.launch {
            val report = FileBrowserTrash.trash(appContext, listOf(entry.path), access)
            if (!isActive) return@launch
            state = state.copy(
                operationRunning = false,
                operationProgress = 0f,
                operationMessage = if (report.failures.isEmpty()) null else "删除失败",
                lastTrash = report.trashed,
                details = null,
            )
            refresh()
        }
    }

    fun undoDelete() {
        val items = state.lastTrash
        val access = state.access ?: return
        if (items.isEmpty() || state.operationRunning) return
        state = state.copy(operationRunning = true, operationProgress = 0f)
        operationJob = scope.launch {
            val failures = FileBrowserTrash.restore(items, access)
            if (!isActive) return@launch
            state = state.copy(
                operationRunning = false,
                operationProgress = 0f,
                operationMessage = if (failures.isEmpty()) null else "有 ${failures.size} 项恢复失败",
                lastTrash = if (failures.isEmpty()) emptyList() else items,
            )
            refresh()
        }
    }

    fun clearTrashUndo() {
        state = state.copy(lastTrash = emptyList())
    }

    fun deleteSelected() {
        if (state.selectedPaths.isEmpty()) return
        val access = state.access ?: return
        if (state.operationRunning) return
        val paths = state.selectedPaths.toList()
        state = state.copy(
            operationRunning = true,
            operationProgress = 0f,
            operationMessage = null,
        )
        operationJob = scope.launch {
            val report = FileBrowserTrash.trash(appContext, paths, access)
            if (!isActive) return@launch
            state = state.copy(
                operationRunning = false,
                operationProgress = 0f,
                operationMessage = if (report.failures.isEmpty()) null else
                    "有 ${report.failures.size} 项失败",
                selectionMode = false,
                selectedPaths = emptySet(),
                lastTrash = report.trashed,
            )
            refresh()
        }
    }

    fun cancel() {
        loadJob?.cancel()
        loadJob = null
        sizeJob?.cancel()
        sizeJob = null
        searchJob?.cancel()
        searchJob = null
        operationJob?.cancel()
        operationJob = null
    }

    fun searchRecursive(query: String) {
        val needle = query.trim()
        if (needle.isEmpty()) return
        val base = state.path
        val access = state.access ?: return
        searchJob?.cancel()
        state = state.copy(
            searchResults = emptyList(),
            searchQuery = needle,
            searchLoading = true,
        )
        searchJob = scope.launch {
            val results = withContext(Dispatchers.IO) {
                FileBrowserSearch.search(base, needle, access)
            }
            if (!isActive) return@launch
            state = state.copy(searchResults = results, searchLoading = false)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        state = state.copy(searchResults = null, searchQuery = null, searchLoading = false)
    }

    fun openSearchResult(entry: FileBrowserEntry) {
        clearSearch()
        if (entry.kind == FileEntryKind.DIRECTORY || entry.kind == FileEntryKind.SYMLINK) {
            navigateTo(entry.path)
        } else {
            showDetails(entry)
        }
    }

    fun computeDirectorySize() {
        if (state.loading || state.directorySizeLoading) return
        val access = state.access ?: return
        val targetPath = state.path
        sizeJob?.cancel()
        state = state.copy(
            directorySizeBytes = null,
            directorySizeLoading = true,
            directorySizeComputed = false,
        )
        sizeJob = scope.launch {
            val size = FileBrowserDirectorySize.compute(targetPath, access)
            if (!isActive || state.path != targetPath) return@launch
            state = state.copy(
                directorySizeBytes = size,
                directorySizeLoading = false,
                directorySizeComputed = true,
            )
        }
    }

    private fun reload(resolveAccess: Boolean = false) {
        loadJob?.cancel()
        sizeJob?.cancel()
        val targetPath = state.path
        loadJob = scope.launch {
            state = state.copy(
                loading = true,
                error = null,
                directorySizeBytes = null,
                directorySizeLoading = false,
                directorySizeComputed = false,
            )
            val access = if (resolveAccess || state.access == null) {
                withContext(Dispatchers.IO) { dataSource.resolveAccess() }
            } else {
                state.access!!
            }
            if (!isActive) return@launch
            state = state.copy(access = access)

            val result = withContext(Dispatchers.IO) { dataSource.list(targetPath, access) }
            if (!isActive) return@launch

            state = when (result) {
                is FileBrowserListingResult.Success -> state.copy(
                    path = targetPath,
                    entries = result.entries,
                    loading = false,
                    error = null,
                    limited = result.limited,
                    truncated = result.truncated,
                )
                is FileBrowserListingResult.Failure -> state.copy(
                    path = targetPath,
                    entries = emptyList(),
                    loading = false,
                    error = result.error,
                    limited = false,
                    truncated = false,
                    directorySizeBytes = null,
                    directorySizeLoading = false,
                    directorySizeComputed = false,
                )
            }
        }
    }
}
