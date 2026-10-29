package com.slideindex.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 悬浮球「闲置隐身」：开关默认关、闲置不透明度默认 0、闲置判定默认 3 秒。
 *
 * 存量用户升级后行为必须完全不变（球始终按「不透明度」显示），所以默认值不能是开。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class FloatBallIdleHiddenTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        repository = testSettingsRepository(context)
    }

    @Test
    fun freshInstall_idleHideDisabledByDefault() {
        val settings = SettingsSnapshotReader.read(emptyPreferences(), context)

        assertFalse(settings.floatBallHideWhenIdle)
        assertEquals(0f, settings.floatBallIdleOpacity, 0.0001f)
        assertEquals(3000, settings.floatBallIdleDelayMs)
    }

    @Test
    fun explicitValues_arePreserved() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_HIDE_WHEN_IDLE to true,
            SettingsPreferenceKeys.FLOAT_BALL_IDLE_OPACITY to 0.2f,
            SettingsPreferenceKeys.FLOAT_BALL_IDLE_DELAY_MS to 1500,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertTrue(settings.floatBallHideWhenIdle)
        assertEquals(0.2f, settings.floatBallIdleOpacity, 0.0001f)
        assertEquals(1500, settings.floatBallIdleDelayMs)
    }

    @Test
    fun outOfRangeValues_areClamped() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_IDLE_OPACITY to 3f,
            SettingsPreferenceKeys.FLOAT_BALL_IDLE_DELAY_MS to 99_000,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertEquals(1f, settings.floatBallIdleOpacity, 0.0001f)
        assertEquals(10_000, settings.floatBallIdleDelayMs)
    }

    @Test
    fun repositoryWrites_roundTrip() = runBlocking {
        assertTrue(repository.setFloatBallHideWhenIdle(true).isSuccess)
        assertTrue(repository.setFloatBallIdleOpacity(0.3f).isSuccess)
        assertTrue(repository.setFloatBallIdleDelayMs(2500).isSuccess)

        val settings = withTimeout(5_000) {
            repository.settings.first { it.floatBallHideWhenIdle }
        }

        assertTrue(settings.floatBallHideWhenIdle)
        assertEquals(0.3f, settings.floatBallIdleOpacity, 0.0001f)
        assertEquals(2500, settings.floatBallIdleDelayMs)
    }
}
