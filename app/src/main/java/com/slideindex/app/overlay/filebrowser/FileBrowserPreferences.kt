package com.slideindex.app.overlay.filebrowser

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.fileBrowserPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "slide_index_file_browser_prefs",
)

@Serializable
internal data class FileBrowserPrefs(
    val pinnedPaths: List<String> = emptyList(),
    val lastPath: String? = null,
    val sortOrder: String = FileSortOrder.NAME_ASC.name,
    val showHidden: Boolean = false,
    val tabs: List<String> = emptyList(),
    val activeTabIndex: Int = 0,
    val hiddenShortcuts: List<String> = emptyList(),
)

internal object FileBrowserPreferences {
    private const val KEY_NAME = "file_browser_prefs"
    private const val MAX_PINS = 8
    private val key = stringPreferencesKey(KEY_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var current by mutableStateOf(FileBrowserPrefs())
        private set

    private var appContext: Context? = null
    private var loaded = false

    fun attach(
        context: Context,
        onLoaded: ((FileBrowserPrefs) -> Unit)? = null,
    ) {
        appContext = context.applicationContext
        if (loaded) {
            onLoaded?.invoke(current)
            return
        }
        loaded = true
        val ctx = context.applicationContext
        scope.launch {
            val prefs = read(ctx)
            current = prefs
            onLoaded?.invoke(prefs)
        }
    }

    fun pinnedOrDefault(internalRoot: String): List<String> {
        val pins = current.pinnedPaths
            .map { FileBrowserPaths.normalize(it, internalRoot) }
            .distinct()
            .take(MAX_PINS)
        return if (pins.isNotEmpty()) pins else defaultPins(internalRoot)
    }

    fun defaultPins(internalRoot: String): List<String> {
        val root = FileBrowserPaths.normalize(internalRoot, internalRoot)
        return listOf(
            "/",
            root,
            "$root/Android/data",
            "$root/Download",
        )
    }

    fun togglePin(path: String) {
        val normalized = FileBrowserPaths.normalize(path)
        val root = FileBrowserStorage.internalStoragePath()
        val base = current.pinnedPaths.ifEmpty { defaultPins(root) }
        val existing = base.map { FileBrowserPaths.normalize(it) }.toMutableList()
        if (existing.contains(normalized)) {
            existing.remove(normalized)
        } else {
            existing.add(normalized)
        }
        update { it.copy(pinnedPaths = existing.distinct().take(MAX_PINS)) }
    }

    fun isPinned(path: String): Boolean {
        val normalized = FileBrowserPaths.normalize(path)
        val root = FileBrowserStorage.internalStoragePath()
        val base = current.pinnedPaths.ifEmpty { defaultPins(root) }
        return base.any { FileBrowserPaths.normalize(it) == normalized }
    }

    fun rememberTabs(paths: List<String>, activeIndex: Int) {
        val normalized = paths.map { FileBrowserPaths.normalize(it) }.distinct().take(6)
        if (normalized.isEmpty()) return
        update {
            it.copy(
                tabs = normalized,
                activeTabIndex = activeIndex.coerceIn(0, normalized.lastIndex),
            )
        }
    }

    fun movePin(from: Int, to: Int, internalRoot: String) {
        val base = current.pinnedPaths.ifEmpty { defaultPins(internalRoot) }
            .map { FileBrowserPaths.normalize(it) }
            .distinct()
            .toMutableList()
        if (from !in base.indices || to !in base.indices || from == to) return
        val item = base.removeAt(from)
        base.add(to, item)
        update { it.copy(pinnedPaths = base.take(MAX_PINS)) }
    }

    fun hiddenSystemShortcuts(): List<String> =
        current.hiddenShortcuts.map { FileBrowserPaths.normalize(it) }.distinct()

    fun isSystemShortcut(path: String, internalRoot: String): Boolean {
        val normalized = FileBrowserPaths.normalize(path, internalRoot)
        return defaultPins(internalRoot).any {
            FileBrowserPaths.normalize(it, internalRoot) == normalized
        } || normalized == FileBrowserPaths.normalize("$internalRoot/Android/obb", internalRoot)
    }

    fun hideShortcut(path: String) {
        val normalized = FileBrowserPaths.normalize(path)
        update {
            it.copy(hiddenShortcuts = (it.hiddenShortcuts + normalized).distinct())
        }
    }

    fun resetHiddenShortcuts() {
        update { it.copy(hiddenShortcuts = emptyList()) }
    }

    fun quickShortcuts(internalRoot: String): List<String> {
        val root = FileBrowserPaths.normalize(internalRoot, internalRoot)
        val hidden = hiddenSystemShortcuts()
        val system = (defaultPins(root) + "$root/Android/obb")
            .map { FileBrowserPaths.normalize(it, root) }
            .filterNot { it in hidden }
        val user = current.pinnedPaths
            .map { FileBrowserPaths.normalize(it, root) }
            .filterNot { it in system }
        return (system + user).distinct().take(MAX_PINS)
    }

    fun rememberLastPath(path: String) {        val normalized = FileBrowserPaths.normalize(path)
        if (current.lastPath == normalized) return
        update { it.copy(lastPath = normalized) }
    }

    fun setSort(order: FileSortOrder) {
        update { it.copy(sortOrder = order.name) }
    }

    fun setShowHidden(show: Boolean) {
        update { it.copy(showHidden = show) }
    }

    private fun update(transform: (FileBrowserPrefs) -> FileBrowserPrefs) {
        current = sanitize(transform(current))
        val ctx = appContext ?: return
        val snapshot = current
        scope.launch { write(ctx, snapshot) }
    }

    private fun sanitize(prefs: FileBrowserPrefs): FileBrowserPrefs = prefs.copy(
        pinnedPaths = prefs.pinnedPaths
            .map { FileBrowserPaths.normalize(it) }
            .filter { it.startsWith("/") }
            .distinct()
            .take(MAX_PINS),
        lastPath = prefs.lastPath?.takeIf { it.startsWith("/") },
    )

    private suspend fun read(context: Context): FileBrowserPrefs {
        val raw = runCatching { context.fileBrowserPrefsDataStore.data.first()[key] }
            .getOrNull()
            ?: return FileBrowserPrefs()
        val decoded = runCatching { json.decodeFromString<FileBrowserPrefs>(raw) }
            .getOrDefault(FileBrowserPrefs())
        return sanitize(decoded)
    }

    private suspend fun write(context: Context, prefs: FileBrowserPrefs) {
        runCatching {
            context.fileBrowserPrefsDataStore.edit { mutable ->
                mutable[key] = json.encodeToString(sanitize(prefs))
            }
        }
    }
}

internal fun FileBrowserPrefs.sortOrderOrDefault(): FileSortOrder =
    FileSortOrder.entries.firstOrNull { it.name == sortOrder } ?: FileSortOrder.NAME_ASC
