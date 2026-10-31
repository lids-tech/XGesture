package com.slideindex.app.overlay.share

import android.content.Context
import android.os.SystemClock
import com.slideindex.app.search.ShareFileTargetResolver
import com.slideindex.app.search.ShareImageTarget
import com.slideindex.app.search.ShareImageTargetResolver
import com.slideindex.app.search.ShareTextTargetResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 分享目标的三种形态：三者的系统目标集合本来就不同，缓存也按它分桶。 */
enum class ShareSheetKind { TEXT, IMAGE, FILE }

/**
 * 分享目标列表的进程级缓存（对齐启动器/分享面板的通行做法：列表常驻内存，
 * 只在"包变了"或缓存过期时重查 PackageManager）。
 *
 * 为什么要缓存：`queryIntentActivities` 之外，`Share*TargetResolver` 还会给每个目标做
 * `getApplicationInfo` + 标签 + 图标解码 + 拼音排序键，几十个目标就是几十次跨进程调用；
 * 面板是高频打开的小工具，每次现场扫一遍完全不值。
 *
 * 失效来源：
 * 1. [onPackagesChanged] —— `PackageChangeReceiver` 收到安装/卸载/更新/组件变更时调用（即时）；
 * 2. [TTL_MS] —— 兜底，防广播被 ROM 拦掉或漏发；
 * 3. 进程重启 —— 缓存本来就在内存里。
 */
object ShareSheetTargetCache {

    private const val TTL_MS = 10 * 60 * 1000L

    private data class Entry(
        val targets: List<ShareImageTarget>,
        val loadedAtMs: Long,
        val version: Long,
    )

    private val entries = ConcurrentHashMap<ShareSheetKind, Entry>()

    /** 包变化计数：后台广播一响就 +1，旧缓存立刻失效（本 App 是单进程，直接函数调用即可）。 */
    @Volatile
    private var version: Long = 0L

    fun onPackagesChanged() {
        version++
        entries.clear()
    }

    /** 同步取缓存（未过期且包没变才返回）。用于面板首帧：命中就能立刻画出列表。 */
    fun peek(kind: ShareSheetKind): List<ShareImageTarget>? {
        val entry = entries[kind] ?: return null
        if (entry.version != version) return null
        if (SystemClock.elapsedRealtime() - entry.loadedAtMs > TTL_MS) return null
        return entry.targets
    }

    /** 取目标列表：缓存有效直接返回，否则重查并写入缓存。必须在后台线程语义下调用（内部切 IO）。 */
    suspend fun load(context: Context, kind: ShareSheetKind): List<ShareImageTarget> =
        withContext(Dispatchers.IO) {
            peek(kind)?.let { return@withContext it }
            val appPackage = context.packageName
            val targets = query(context, kind)
                // 分享给自己没有意义，还会在面板里占一格；在缓存层过滤，免得每次渲染都判一遍。
                .filterNot { it.packageName == appPackage }
            entries[kind] = Entry(targets, SystemClock.elapsedRealtime(), version)
            targets
        }

    private fun query(context: Context, kind: ShareSheetKind): List<ShareImageTarget> = when (kind) {
        ShareSheetKind.TEXT -> ShareTextTargetResolver.listTargets(context)
        ShareSheetKind.IMAGE -> ShareImageTargetResolver.listTargets(context)
        ShareSheetKind.FILE -> ShareFileTargetResolver.listTargets(context)
    }

    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 预热最常见的「文本分享」目标列表，让第一次打开面板也命中缓存。
     *
     * 延迟 [delayMs] 再做：无障碍服务刚连上时是启动高峰期，别在这个点抢一次
     * `queryIntentActivities` + 图标加载。命中缓存时这次调用是空操作。
     */
    fun warmUpAsync(context: Context, delayMs: Long = 3_000L) {
        val appContext = context.applicationContext
        warmUpScope.launch {
            runCatching {
                if (delayMs > 0) delay(delayMs)
                load(appContext, ShareSheetKind.TEXT)
            }
        }
    }
}
