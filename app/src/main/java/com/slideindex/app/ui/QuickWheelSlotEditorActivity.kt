package com.slideindex.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.settings.QuickWheelCodec
import com.slideindex.app.settings.QuickWheelSlot
import com.slideindex.app.settings.updateSlot
import com.slideindex.app.ui.compose.LocalAppDependencies
import com.slideindex.app.ui.theme.SlideIndexTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 「轮盘容器」配置页（独立 Activity）。
 *
 * 由浮层里的编辑层通过 [FLAG_ACTIVITY_NEW_TASK] 拉起；浮层必须先收起，否则会被
 * TYPE_APPLICATION_OVERLAY 的轮盘压在下面。
 */
@AndroidEntryPoint
class QuickWheelSlotEditorActivity : ComponentActivity() {

    @Inject lateinit var deps: AppDependencies

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val wheelId = intent.getStringExtra(EXTRA_WHEEL_ID).orEmpty()
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val settings = deps.settingsRepository.readSnapshot()
        val wheel = settings.launcher.quickWheels.firstOrNull { it.id == wheelId }
        if (wheel == null || path.isBlank()) {
            finish()
            return
        }
        setContent {
            // ⚠️ 必须提供 AppDependencies：GestureActionPickerScreen（动作选择页）通过
            // LocalAppDependencies 拿 AppRepository 来列应用，缺了它一点「功能类型」就崩。
            CompositionLocalProvider(LocalAppDependencies provides deps) {
                SlideIndexTheme {
                    QuickWheelSlotEditorScreen(
                        wheel = wheel,
                        path = path,
                        appSettings = settings,
                        onExit = { finish() },
                        onSave = { slot ->
                            lifecycleScope.launch {
                                saveSlot(wheelId, path, slot)
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * 任务复用时会走这里而不是 [onCreate]（`FLAG_ACTIVITY_NEW_TASK` 的既有实例会被拉到前台）。
     *
     * 不重建的话，页面还停在上一次编辑的那个容器上 —— 表现就是"新加的格子保存后没生效"。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    private suspend fun saveSlot(wheelId: String, path: String, slot: QuickWheelSlot) {
        val repo = deps.settingsRepository
        val wheels = repo.readSnapshot().launcher.quickWheels
        val targetIndex = QuickWheelCodec.parsePrimaryIndex(path)
        val updated = wheels.map { wheel ->
            if (wheel.id != wheelId) {
                wheel
            } else {
                val next = wheel.updateSlot(path) { slot }
                when {
                    // 正常情况：命中已有容器 / 命中"追加位"。
                    next !== wheel -> next
                    // 兜底：追加位要求 index == slots.size，快照数量一旦对不上就会静默不改。
                    // 这里直接按索引补位 / 追加，避免"保存了但格子没 +1"。
                    targetIndex != null && targetIndex >= wheel.slots.size ->
                        wheel.copy(slots = wheel.slots + slot)
                    else -> wheel
                }
            }
        }
        runCatching { repo.setQuickWheels(updated) }
            .onFailure { Log.w(TAG, "setQuickWheels 失败", it) }
        Log.i(
            TAG,
            "save path=$path wheel=${wheelId.take(8)} " +
                    "before=${wheels.firstOrNull { it.id == wheelId }?.slots?.size} " +
                    "after=${updated.firstOrNull { it.id == wheelId }?.slots?.size}",
        )
    }

    companion object {
        const val EXTRA_WHEEL_ID = "extra_wheel_id"
        const val EXTRA_PATH = "extra_path"
        private const val TAG = "QwSlotEditor"
    }
}