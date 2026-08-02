package com.deadaccurate.app.ui

/** User intents from the capture screen, grouped to keep signatures small. */
data class CaptureActions(
    val onToggleCapture: () -> Unit,
    val onDismissInputLost: () -> Unit,
    val onSetBph: (Int) -> Unit,
    val onSetGateTrim: (Float) -> Unit,
    val onRecalibrate: () -> Unit,
)
