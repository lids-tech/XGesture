package com.slideindex.app.stash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日历同步的**落盘**诊断日志。
 *
 * 为什么不用 logcat：真机上 overlay 进程的 Log.i 有时在 logcat 里看不到（MIUI 的日志策略 +
 * 缓冲轮转都能把行吃掉），排查"点了没反应"时只能靠猜。落一个文件到 filesDir，
 * 用 `adb shell run-as com.slideindex.app cat files/calendar_sync_debug.log` 就能全量拿到。
 */
object CalendarSyncDebug {
    private const val TAG = "CalendarTodoSync"
    private const val FILE_NAME = "calendar_sync_debug.log"
    private const val MAX_BYTES = 64 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                file.writeText("")
            }
            file.appendText("${timeFormat.format(Date())}  $message\n")
        }
    }
}
