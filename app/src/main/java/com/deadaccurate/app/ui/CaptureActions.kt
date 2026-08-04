package com.deadaccurate.app.ui

import android.net.Uri
import com.deadaccurate.app.settings.InputPreference

/** User intents from the capture screen, grouped to keep signatures small. */
data class CaptureActions(
    val onToggleCapture: () -> Unit,
    val onDismissInputLost: () -> Unit,
    /** null = return to auto-detection. */
    val onSetBphOverride: (Int?) -> Unit,
    val onSetGateTrim: (Float) -> Unit,
    val onRecalibrate: () -> Unit,
    val onSetInputPreference: (InputPreference) -> Unit,
    val onDismissOnboarding: () -> Unit,
    val onReplayFile: (Uri) -> Unit,
    val onRunDemo: () -> Unit,
    val onExportSession: () -> Unit,
    val onExportHandled: () -> Unit,
    /** Delta in s/day; 0 resets the calibration. */
    val onAdjustClockCal: (Float) -> Unit,
)
