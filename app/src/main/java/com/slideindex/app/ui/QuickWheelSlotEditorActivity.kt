package com.slideindex.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.slideindex.app.di.AppDependencies
import com.slideindex.app.settings.QuickWheelCodec
import com.slideindex.app.ui.theme.SlideIndexTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import com.slideindex.app.settings.updateSlot
import kotlinx.coroutines.launch

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
            SlideIndexTheme {
                QuickWheelSlotEditorScreen(
                    wheel = wheel,
                    path = path,
                    appSettings = settings,
                    onExit = { finish() },
                    onSave = { slot ->
                        lifecycleScope.launch {
                            val wheels = deps.settingsRepository
                                .readSnapshot()
                                .launcher
                                .quickWheels
                            deps.settingsRepository.setQuickWheels(
                                wheels.map { w ->
                                    if (w.id != wheelId) w else w.updateSlot(path) { slot }
                                },
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_WHEEL_ID = "extra_wheel_id"
        const val EXTRA_PATH = "extra_path"
    }
}