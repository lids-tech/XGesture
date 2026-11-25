package com.slideindex.app.stash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.slideindex.app.util.finishWithoutTransition

/**
 * 日历读写权限的瞬发跳板。
 *
 * overlay 面板窗**不能**弹运行时权限对话框（只能由 Activity 发起），而日历同步要在
 * 「面板打开瞬间」跑，所以第一次需要权限时由这里拉一次系统对话框，授权/拒绝都立刻结束，
 * 下次打开面板再看权限结果。用户已经拒绝过时不再重复弹（交给系统"不再询问"策略）。
 */
class CalendarSyncPermissionActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        finishWithoutTransition()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CalendarTodoSync.hasPermission(this)) {
            finishWithoutTransition()
            return
        }
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            ),
        )
    }

    companion object {
        @Volatile
        private var requestedOnce = false

        /** 已经弹过一次就返回 false（避免每次开面板都骚扰）。 */
        fun requestIfNeeded(context: Context): Boolean {
            if (CalendarTodoSync.hasPermission(context)) return false
            if (requestedOnce) return false
            requestedOnce = true
            runCatching {
                context.startActivity(
                    Intent(context, CalendarSyncPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return true
        }

        /** 权限检查（给调用方复用）。 */
        fun granted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
