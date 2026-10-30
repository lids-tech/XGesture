package com.slideindex.app.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.slideindex.app.R
import com.slideindex.app.overlay.FloatingPointerBounds
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.ui.miuix.CardItem
import com.slideindex.app.ui.miuix.groupedCardItems
import com.slideindex.app.ui.settings.components.settingsCardScopeItem
import kotlin.math.roundToInt

fun floatBallPickAdvancedCardItems(
    settings: AppSettings,
    onPickBottomTransitionChange: (Float) -> Unit,
    onPointerSpeedChange: (Float) -> Unit,
    onPointerSpeedVerticalChange: (Float) -> Unit,
    onPickAccelZoneChange: (Float) -> Unit,
    onPointerSlopChange: (Float) -> Unit,
    onHoverPauseDelayMsChange: (Int) -> Unit,
    onRegionalCancelSlopDpChange: (Float) -> Unit,
): List<CardItem> = listOf(
    settingsCardScopeItem("bottom-transition") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_pick_bottom_transition),
            value = settings.floatBallPickBottomTransitionFraction,
            valueRange = 0.05f..0.22f,
            steps = 8,
            enabled = true,
            label = stringResource(
                R.string.floating_pointer_percent_value,
                (settings.floatBallPickBottomTransitionFraction * 100).roundToInt(),
            ),
            onValueChange = onPickBottomTransitionChange,
        )
    },
    settingsCardScopeItem("pointer-speed") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_pointer_speed),
            value = settings.floatBallPointerSpeedFraction,
            valueRange = FloatingPointerBounds.PICK_POINTER_SPEED_MIN..
                FloatingPointerBounds.SENSITIVITY_MAX,
            // 0%–75%，每档 1%。
            steps = 74,
            enabled = true,
            label = stringResource(
                R.string.floating_pointer_percent_value,
                (settings.floatBallPointerSpeedFraction * 100).roundToInt(),
            ),
            onValueChange = onPointerSpeedChange,
        )
    },
    settingsCardScopeItem("pointer-speed-vertical") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_pointer_speed_vertical),
            value = settings.floatBallPointerSpeedVerticalFraction,
            valueRange = FloatingPointerBounds.PICK_POINTER_SPEED_MIN..
                FloatingPointerBounds.SENSITIVITY_MAX,
            // 0%–75%，每档 1%。
            steps = 74,
            enabled = true,
            label = stringResource(
                R.string.floating_pointer_percent_value,
                (settings.floatBallPointerSpeedVerticalFraction * 100).roundToInt(),
            ),
            onValueChange = onPointerSpeedVerticalChange,
        )
    },
    settingsCardScopeItem("pick-accel-zone") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_pick_accel_zone),
            value = settings.floatBallPickAccelZoneFraction,
            valueRange = FloatingPointerBounds.PICK_ACCEL_ZONE_MIN..
                FloatingPointerBounds.PICK_ACCEL_ZONE_MAX,
            // 0%–100%，每档 5%。
            steps = 19,
            enabled = true,
            label = stringResource(
                R.string.floating_pointer_percent_value,
                (settings.floatBallPickAccelZoneFraction * 100).roundToInt(),
            ),
            onValueChange = onPickAccelZoneChange,
        )
    },
    settingsCardScopeItem("pointer-slop") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_pointer_slop),
            value = settings.floatBallPointerSlopDp,
            // 下限放到 1dp：默认仍是 4dp，但想把"球跟手前的死区"压到几乎为零时可以调到这里
            // （0dp 会让按下抖动直接变成拖动，长按/单击都会被吞，所以不放 0）。
            valueRange = FloatingPointerBounds.PICK_SLOP_MIN_DP..
                FloatingPointerBounds.PICK_SLOP_MAX_DP,
            steps = (FloatingPointerBounds.PICK_SLOP_MAX_DP -
                FloatingPointerBounds.PICK_SLOP_MIN_DP).toInt() - 1,
            enabled = true,
            label = stringResource(R.string.float_ball_size_value, settings.floatBallPointerSlopDp),
            onValueChange = onPointerSlopChange,
        )
    },
    settingsCardScopeItem("hover-pause-delay") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_hover_pause_delay),
            value = settings.floatBallHoverPauseDelayMs.toFloat(),
            valueRange = 200f..1000f,
            steps = 15,
            enabled = true,
            label = stringResource(
                R.string.float_ball_pick_panel_animation_ms_value,
                settings.floatBallHoverPauseDelayMs,
            ),
            onValueChange = { onHoverPauseDelayMsChange(it.roundToInt()) },
        )
    },
    settingsCardScopeItem("regional-cancel-slop") {
        SettingsSliderRow(
            title = stringResource(R.string.float_ball_regional_cancel_slop),
            value = settings.floatBallRegionalCancelSlopDp,
            valueRange = 3f..30f,
            steps = 26,
            enabled = true,
            label = stringResource(
                R.string.float_ball_size_value,
                settings.floatBallRegionalCancelSlopDp,
            ),
            onValueChange = onRegionalCancelSlopDpChange,
        )
    },
)

fun LazyListScope.floatBallPickAdvancedSettingsGroup(
    keyPrefix: String,
    settings: AppSettings,
    onPickBottomTransitionChange: (Float) -> Unit,
    onPointerSpeedChange: (Float) -> Unit,
    onPointerSpeedVerticalChange: (Float) -> Unit,
    onPickAccelZoneChange: (Float) -> Unit,
    onPointerSlopChange: (Float) -> Unit,
    onHoverPauseDelayMsChange: (Int) -> Unit,
    onRegionalCancelSlopDpChange: (Float) -> Unit,
) {
    groupedCardItems(
        keyPrefix = keyPrefix,
        items = floatBallPickAdvancedCardItems(
            settings = settings,
            onPickBottomTransitionChange = onPickBottomTransitionChange,
            onPointerSpeedChange = onPointerSpeedChange,
            onPointerSpeedVerticalChange = onPointerSpeedVerticalChange,
            onPickAccelZoneChange = onPickAccelZoneChange,
            onPointerSlopChange = onPointerSlopChange,
            onHoverPauseDelayMsChange = onHoverPauseDelayMsChange,
            onRegionalCancelSlopDpChange = onRegionalCancelSlopDpChange,
        ),
    )
}
