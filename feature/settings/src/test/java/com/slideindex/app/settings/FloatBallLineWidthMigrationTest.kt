package com.slideindex.app.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 触发区宽度从「屏宽百分比」迁到 dp。
 *
 * 老用户的旧值按固定基准折算成 dp：如果按当前窗口宽度折算，折叠屏展开 / 横屏时
 * 同一个旧值会被放大，正是这次要修的问题。新键一旦存在就优先，旧键只做兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class FloatBallLineWidthMigrationTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun freshInstall_usesDpDefault() {
        val settings = SettingsSnapshotReader.read(emptyPreferences(), context)

        assertEquals(FloatBallSettings.DEFAULT_LINE_WIDTH_DP, settings.floatBallLineWidthDp, 0.0001f)
        assertEquals(24f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun legacyFraction_isConvertedWithFixedReferenceWidth() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_FRACTION to 0.20f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        // 20% × 400dp 基准 = 80dp，与横竖屏 / 折叠展开无关。
        assertEquals(80f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun legacyDefaultFraction_fallsBackToComfortableMinimum() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_FRACTION to 0.04f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertEquals(24f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun legacyTinyFraction_staysAtLegacyMinimum() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_FRACTION to 0.01f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        // 旧值折算结果低于 24dp 时沿用旧实现的 24dp 下限，不能让老用户升级后触发带变窄。
        assertEquals(24f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun storedZeroDp_isAllowed() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_DP to 0f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        // 新设置允许调到 0（只显示线、不接收触摸），不能再被迁移下限抬回 24。
        assertEquals(0f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun legacyHugeFraction_isClampedToUpperBound() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_FRACTION to 0.50f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertEquals(FloatBallSettings.MAX_LINE_WIDTH_DP, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun newDpKey_winsOverLegacyFraction() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_DP to 32f,
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_FRACTION to 0.50f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertEquals(32f, settings.floatBallLineWidthDp, 0.0001f)
    }

    @Test
    fun storedDp_outOfRange_isClamped() {
        val prefs = mutablePreferencesOf(
            SettingsPreferenceKeys.FLOAT_BALL_LINE_WIDTH_DP to 500f,
        )

        val settings = SettingsSnapshotReader.read(prefs, context)

        assertEquals(FloatBallSettings.MAX_LINE_WIDTH_DP, settings.floatBallLineWidthDp, 0.0001f)
    }
}
