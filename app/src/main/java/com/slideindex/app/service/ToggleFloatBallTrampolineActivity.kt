package com.slideindex.app.service

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.overlay.FloatBallVisibilityToggle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「切换悬浮球」的外部调用入口（Tasker / MacroDroid / adb）。
 *
 * 只做一件事：转给 [FloatBallVisibilityToggle]，与手势动作共用同一份开关逻辑，
 * 避免两处各写一份"取反 + 落盘 + 提示"，久了两边行为不一致。
 *
 * 组件与 Action 名见 `ExternalInvocationCatalog`，外部调用页会自动生成 adb 命令。
 */
@AndroidEntryPoint
class ToggleFloatBallTrampolineActivity : ComponentActivity() {

    @Inject
    lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            FloatBallVisibilityToggle.toggle(this@ToggleFloatBallTrampolineActivity, deps.settingsRepository)
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val ACTION_TOGGLE_FLOAT_BALL = "com.slideindex.app.action.TOGGLE_FLOAT_BALL"
    }
}
