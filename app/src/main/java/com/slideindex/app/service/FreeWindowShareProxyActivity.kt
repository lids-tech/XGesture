package com.slideindex.app.service

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.slideindex.app.util.FreeWindowLauncher

/**
 * vivo / OriginOS 小窗的启动壳。
 *
 * 机制（借系统的手，本应用不申请任何窗口参数）：
 * 1. [FreeWindowLauncher] 把"要打开的 Intent"包进一次系统分享（`Intent.createChooser`），
 *    接收方强制指定为本 Activity；
 * 2. OriginOS 的「小窗分享」会把这次分享的**接收方**开成小窗——也就是这个壳；
 * 3. 壳在 `onCreate` 里把 [Intent.EXTRA_INTENT] 取出来，从"正处在小窗里"的上下文启动目标应用，
 *    目标于是继续落在小窗里；
 * 4. 壳退出。它不画任何东西，整个过程用户只看到目标应用以小窗出现。
 *
 * 因此本 Activity 的清单声明不能随便改：必须 `exported`（系统分享流程是别的进程）、
 * 声明 `SEND` 过滤器（否则不算分享目标）、透明、独占一个任务（`singleInstance`）。
 * 详见 AndroidManifest 里这条声明上的注释。
 *
 * 它是导出的，所以也会收到别的应用发来的真实分享：没有 [FreeWindowLauncher.EXTRA_FREEWINDOW_REQUEST]
 * 标识的一律不处理，避免把别人的分享正文当成包名去启动。
 */
class FreeWindowShareProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRequest(intent)
    }

    /**
     * 壳是 `singleInstance` 的：上一次请求的壳还没走完时又来一次，系统走的是 `onNewIntent`
     * 而不是新建实例。不接这一手，那次小窗请求就会静默丢失（GEVO 的壳同样处理 onNewIntent）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRequest(intent)
    }

    private fun handleRequest(source: Intent) {
        if (!source.getBooleanExtra(FreeWindowLauncher.EXTRA_FREEWINDOW_REQUEST, false)) {
            Log.d(TAG, "ignore non-freeform share from ${callingPackage ?: "unknown"}")
            finish()
            return
        }

        val target = readTargetIntent(source)
        if (target == null) {
            Log.w(TAG, "freeform request without a target intent")
            finish()
            return
        }

        try {
            // 原样转发：flag 已在 FreeWindowLauncher.normalizeForwardedIntent 归一成
            // NEW_TASK + SINGLE_TOP，这里再动它就会改变系统对"新窗口"的判定。
            startActivity(target)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "target activity not found", error)
        } catch (error: SecurityException) {
            Log.e(TAG, "target launch denied", error)
        } catch (error: Exception) {
            Log.e(TAG, "target launch failed", error)
        } finally {
            finish()
        }
    }

    /** [Intent.EXTRA_INTENT] 优先；取不到时退回"用 EXTRA_TEXT 的包名做 launcher intent"。 */
    private fun readTargetIntent(source: Intent): Intent? {
        val forwarded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        if (forwarded != null) return Intent(forwarded)

        val packageName = source.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
    }

    private companion object {
        const val TAG = "FreeWindowShareProxy"
    }
}
