package com.slideindex.app.stash

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * 闪念「待办」与系统日历的双向同步（用户 2026-11 需求，MVP 版本）。
 *
 * 需求边界（用户明确拍板）：
 * 1. **日历 → 待办**：面板打开瞬间同步一次日本日历里「今天 + 未来 7 天」的日程，
 *    变成闪念里的待办条目；
 * 2. **待办 → 日历**：闪念里新增的待办同步到系统日历（不自建日历，用系统已有可写日历）；
 * 3. **完成联动**：闪念里点完成 → 系统日历对应事件标题前面加 `[已完成]`（取消完成则去掉）。
 * 其它（删除联动、日历侧改完成回同步、冲突合并）**不做** —— 用户明确"其他不用管"。
 *
 * 关键取舍：
 * - **只排除节假日/农历日历**（生日日历要保留）：按日历显示名过滤关键词；
 * - 待办没有"时间"概念，所以写日历时：**有提醒 → 用提醒时间；没提醒 → 当天全天事件**（方案 A）；
 * - 映射表复用 [StashMetaStore.sources]：条目 → `calendar:<calendarId>:<eventId>`，
 *   不新增存储、不新增迁移；
 * - 防循环：**导入出来的条目带 calendar: 来源**，所以"新增待办 → 写日历"那一步天然跳过它们。
 */
object CalendarTodoSync {
    private const val TAG = "CalendarTodoSync"

    /** 条目来源前缀：`calendar:<calendarId>:<eventId>`。 */
    const val SOURCE_PREFIX = "calendar:"

    /** 完成态写在标题最前面的前缀（系统日历没有"已完成"字段，只能借标题表达）。 */
    const val DONE_PREFIX = "[已完成] "

    /** 导入的时间窗口：今天 + 未来 7 天。 */
    private const val IMPORT_DAYS = 8

    /** 同一进程内两次同步的最小间隔（面板反复开关时不要每次全量查）。 */
    private const val MIN_SYNC_INTERVAL_MS = 20_000L

    /** 节假日/农历类日历（过滤掉，避免把节假日与农历纪念日灌成待办）；生日不在此列。 */
    private val EXCLUDED_CALENDAR_NAME = Pattern.compile(
        "节假日|假日|农历|黄历|holiday|lunar|festival",
        Pattern.CASE_INSENSITIVE,
    )

    @Volatile
    private var lastSyncAtMs = 0L

    /**
     * 进程级 IO 作用域（**不是** Compose 的 rememberCoroutineScope）。
     *
     * ⚠️ 踩过的坑：写日历原本用的是面板的 `rememberCoroutineScope`，用户**保存完立刻收起面板**
     * 时组合被销毁 → 协程在 `withContext(IO)` 处直接取消，事件永远没写进日历、日志也只停在
     * "pushTodoToCalendar() called"（真机日志实锤）。日历写入必须活过面板销毁，所以挂进程级作用域。
     */
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    /** 异步版：新增待办后调用；与面板生命周期无关，面板收起也会写完。 */
    fun pushTodoToCalendarAsync(
        context: Context,
        entryId: String,
        title: String,
        reminderAtMs: Long?,
    ) {
        val appContext = context.applicationContext
        ioScope.launch {
            CalendarSyncDebug.log(appContext, "pushTodoToCalendarAsync start entry=$entryId")
            runCatching {
                pushTodoToCalendar(appContext, entryId, title, reminderAtMs)
            }.onFailure {
                CalendarSyncDebug.log(appContext, "pushTodoToCalendarAsync threw: ${it.message}")
            }
            CalendarSyncDebug.log(appContext, "pushTodoToCalendarAsync end entry=$entryId")
        }
    }

    /** 异步版：完成态写回日历（同上，不能挂在面板作用域上）。 */
    fun setDoneInCalendarAsync(context: Context, entryId: String, done: Boolean) {
        val appContext = context.applicationContext
        ioScope.launch {
            runCatching {
                setDoneInCalendar(appContext, entryId, done)
            }.onFailure {
                CalendarSyncDebug.log(appContext, "setDoneInCalendarAsync threw: ${it.message}")
            }
        }
    }

    /** 是否已授予读写日历权限。 */
    fun hasPermission(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 面板打开时调一次：把系统日历里「今天 + 未来 7 天」的日程补成闪念待办。
     *
     * @return 本次新增的待办条数；无权限/查询失败返回 0。
     */
    suspend fun syncFromCalendar(context: Context, force: Boolean = false): Int {
        CalendarSyncDebug.log(context, "syncFromCalendar() called force=$force")
        if (!hasPermission(context)) {
            Log.i(TAG, "sync skipped: no calendar permission")
            CalendarSyncDebug.log(context, "sync skipped: no calendar permission")
            return 0
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAtMs < MIN_SYNC_INTERVAL_MS) return 0
        lastSyncAtMs = now
        return withContext(Dispatchers.IO) {
            runCatching { importRange(context.applicationContext) }
                .onFailure { Log.w(TAG, "syncFromCalendar failed: ${it.message}", it) }
                .getOrDefault(0)
        }
    }

    private suspend fun importRange(context: Context): Int {
        val repo = StashAccess.repository ?: return 0
        val meta = StashAccess.metaRepository ?: return 0
        val resolver = context.contentResolver

        val calendarIds = writableOrVisibleCalendarIds(resolver)
        if (calendarIds.isEmpty()) {
            Log.i(TAG, "import: no readable calendars")
            return 0
        }

        val beginMs = startOfTodayMs()
        val endMs = beginMs + IMPORT_DAYS * DAY_MS
        // 已有来源的条目不重复导入（含此前导入过、以及用户自己写进日历的待办）。
        val knownSources = meta.store.value.sources // entryId -> source
        val events = queryInstances(resolver, calendarIds, beginMs, endMs)
        Log.i(
            TAG,
            "import: calendars=${calendarIds.size} events=${events.size} known=${knownSources.size}",
        )

        var added = 0
        events.forEach { event ->
            val source = "$SOURCE_PREFIX${event.calendarId}:${event.eventId}"
            if (knownSources.containsValue(source)) return@forEach
            val rawTitle = event.title?.trim().orEmpty()
            if (rawTitle.isEmpty()) return@forEach
            val isDone = rawTitle.startsWith(DONE_PREFIX)
            val title = if (isDone) rawTitle.removePrefix(DONE_PREFIX).trim() else rawTitle
            if (title.isEmpty()) return@forEach
            val entry = repo.addText(title) ?: return@forEach
            meta.setTags(entry.id, listOf(StashMetaRepository.TODO_TAG_NAME))
            meta.setSource(entry.id, source)
            if (isDone) meta.setDone(entry.id, true)
            added++
        }
        if (added > 0) {
            // 条目/元数据都是 StateFlow，面板自动重组刷新，不需要额外刷新钩子。
            Log.i(TAG, "imported $added calendar event(s) as todo")
        }
        return added
    }

    /**
     * 闪念里新增的待办 → 写进系统日历。
     *
     * 只在**没有 calendar 来源**时写（导入出来的条目已经有对应事件，不能再建一条）。
     *
     * @return 写入成功的事件 id（同时已把来源写回条目）；失败返回 null。
     */
    suspend fun pushTodoToCalendar(
        context: Context,
        entryId: String,
        title: String,
        reminderAtMs: Long?,
    ): Long? {
        CalendarSyncDebug.log(
            context,
            "pushTodoToCalendar() called entry=$entryId title=${title.take(20)} reminder=$reminderAtMs",
        )
        if (!hasPermission(context)) {
            Log.i(TAG, "push skipped: no calendar permission")
            CalendarSyncDebug.log(context, "push skipped: no calendar permission")
            return null
        }
        val meta = StashAccess.metaRepository
        if (meta == null) {
            Log.i(TAG, "push skipped: meta repository unavailable")
            return null
        }
        if (meta.sourceOf(entryId)?.startsWith(SOURCE_PREFIX) == true) {
            Log.i(TAG, "push skipped: entry $entryId already has calendar source")
            return null
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) {
            Log.i(TAG, "push skipped: empty title")
            return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.applicationContext.contentResolver
                val candidates = writableCalendarCandidates(resolver)
                Log.i(
                    TAG,
                    "push entry=$entryId title=${cleanTitle.take(30)} candidates=" +
                        candidates.joinToString { "${it.id}:${it.name}(${it.accountType})" },
                )
                if (candidates.isEmpty()) {
                    Log.w(TAG, "push failed: no writable calendar")
                    return@withContext null
                }
                // 依次尝试：优先本地日历（MIUI 日历一定显示），失败再换下一个。
                for (calendar in candidates) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendar.id)
                        put(CalendarContract.Events.TITLE, cleanTitle)
                        put(
                            CalendarContract.Events.DESCRIPTION,
                            "闪念待办 · xg-stash-entry:$entryId",
                        )
                        if (reminderAtMs != null) {
                            put(CalendarContract.Events.DTSTART, reminderAtMs)
                            put(CalendarContract.Events.DTEND, reminderAtMs + 30 * 60_000L)
                            put(CalendarContract.Events.ALL_DAY, 0)
                            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        } else {
                            // 方案 A：没有提醒 → 当天全天事件。
                            // 全天事件的时间戳按**本地时区的当天 0 点**写，时区也写本地
                            // （之前写 UTC，MIUI 上会把全天事件显示成前一天）。
                            put(CalendarContract.Events.DTSTART, startOfTodayMs())
                            put(CalendarContract.Events.ALL_DAY, 1)
                            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                        }
                        put(CalendarContract.Events.HAS_ALARM, if (reminderAtMs != null) 1 else 0)
                    }
                    val uri = runCatching {
                        resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    }.onFailure {
                        Log.w(TAG, "insert into ${calendar.name}(${calendar.id}) failed: ${it.message}", it)
                    }.getOrNull() ?: continue
                    val eventId = ContentUris.parseId(uri)
                    meta.setSource(entryId, "$SOURCE_PREFIX${calendar.id}:$eventId")
                    Log.i(
                        TAG,
                        "pushed entry $entryId -> event ${calendar.id}:$eventId " +
                            "calendar=${calendar.name}",
                    )
                    return@withContext eventId
                }
                Log.w(TAG, "push failed: all candidate calendars rejected the insert")
                null
            }.onFailure {
                Log.w(TAG, "pushTodoToCalendar crashed: ${it.message}", it)
            }.getOrNull()
        }
    }

    /**
     * 完成/取消完成 → 改系统日历事件的标题前缀 `[已完成]`。
     *
     * 只有带 calendar 来源的条目才有对应事件；查不到/没权限时静默返回 false。
     */
    suspend fun setDoneInCalendar(context: Context, entryId: String, done: Boolean): Boolean {
        if (!hasPermission(context)) return false
        val meta = StashAccess.metaRepository ?: return false
        val source = meta.sourceOf(entryId)?.takeIf { it.startsWith(SOURCE_PREFIX) } ?: return false
        val parts = source.removePrefix(SOURCE_PREFIX).split(':')
        if (parts.size < 2) return false
        val eventId = parts[1].toLongOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.applicationContext.contentResolver
                val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val currentTitle = resolver.query(
                    eventUri,
                    arrayOf(CalendarContract.Events.TITLE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (currentTitle == null) return@withContext false
                val stripped = currentTitle.removePrefix(DONE_PREFIX)
                val nextTitle = if (done) DONE_PREFIX + stripped else stripped
                if (nextTitle == currentTitle) return@withContext true
                val values = ContentValues().apply {
                    put(CalendarContract.Events.TITLE, nextTitle)
                }
                val rows = resolver.update(eventUri, values, null, null)
                Log.i(TAG, "setDoneInCalendar entry=$entryId done=$done rows=$rows")
                rows > 0
            }.onFailure {
                Log.w(TAG, "setDoneInCalendar failed: ${it.message}", it)
            }.getOrDefault(false)
        }
    }

    /* ------------------------------ 日历查询 ------------------------------ */

    private data class EventRef(val calendarId: Long, val eventId: Long, val title: String?)

    /** 所有「可见且可读」的日历 id（跳过节假日/农历类）。 */
    private fun writableOrVisibleCalendarIds(resolver: ContentResolver): List<Long> {
        val result = ArrayList<Long>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex).orEmpty()
                if (EXCLUDED_CALENDAR_NAME.matcher(name).find()) continue
                result += cursor.getLong(idIndex)
            }
        }
        return result
    }

    /** 写入目标：优先**本地日历**（MIUI 日历必定显示）、其次主日历、最后第一个可写日历。 */
    private data class CalendarCandidate(
        val id: Long,
        val name: String,
        val accountType: String,
        val isLocal: Boolean,
    )

    private fun writableCalendarCandidates(resolver: ContentResolver): List<CalendarCandidate> {
        val all = ArrayList<CalendarCandidate>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            val accessIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                val writable =
                    cursor.getInt(accessIndex) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                if (!writable) continue
                val name = cursor.getString(nameIndex).orEmpty()
                if (EXCLUDED_CALENDAR_NAME.matcher(name).find()) continue
                val accountType = cursor.getString(accountIndex).orEmpty()
                all += CalendarCandidate(
                    id = cursor.getLong(idIndex),
                    name = name,
                    accountType = accountType,
                    isLocal = accountType.equals(CalendarContract.ACCOUNT_TYPE_LOCAL, ignoreCase = true) ||
                        accountType.contains("local", ignoreCase = true),
                )
            }
        }
        // 排序：本地 → 主日历 → 其余（保持查询顺序）。
        val primaryIds = HashSet<Long>()
        runCatching {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                while (cursor.moveToNext()) {
                    if (cursor.getInt(primaryIndex) == 1) primaryIds += cursor.getLong(idIndex)
                }
            }
        }
        return all.sortedWith(
            compareByDescending<CalendarCandidate> { it.isLocal }
                .thenByDescending { primaryIds.contains(it.id) },
        )
    }


    private fun queryInstances(
        resolver: ContentResolver,
        calendarIds: List<Long>,
        beginMs: Long,
        endMs: Long,
    ): List<EventRef> {
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginMs)
        ContentUris.appendId(builder, endMs)
        val selection = CalendarContract.Instances.CALENDAR_ID + " IN (" +
            TextUtils.join(",", calendarIds) + ")"
        val result = ArrayList<EventRef>()
        resolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
            ),
            selection,
            null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { cursor ->
            val calIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val eventIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val seen = HashSet<String>()
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(calIndex)
                val eventId = cursor.getLong(eventIndex)
                // 重复日程会命中多条 instance，按事件去重（一条待办只对应一个事件）。
                if (!seen.add("$calId:$eventId")) continue
                result += EventRef(
                    calendarId = calId,
                    eventId = eventId,
                    title = cursor.getString(titleIndex),
                )
            }
        }
        return result
    }

    /* ------------------------------ 时间工具 ------------------------------ */

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun startOfTodayMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
