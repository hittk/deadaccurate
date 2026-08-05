package com.deadaccurate.app.ui

import android.net.Uri
import com.deadaccurate.app.TimegrapherViewModel
import com.deadaccurate.app.settings.AnalysisMode
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
    val onSetAnalysisMode: (AnalysisMode) -> Unit,
    val onDismissOnboarding: () -> Unit,
    val onReplayFile: (Uri) -> Unit,
    val onRunDemo: () -> Unit,
    val onExportSession: () -> Unit,
    val onExportHandled: () -> Unit,
    val onRecordDiagnostic: () -> Unit,
    /** Delta in s/day; 0 resets the calibration. */
    val onAdjustClockCal: (Float) -> Unit,
    val watchLog: WatchLogActions,
)

/** Intents for finishing a measurement and the per-watch history log. */
data class WatchLogActions(
    val onOpenSaveDialog: () -> Unit,
    val onDismissSaveDialog: () -> Unit,
    /** (watchId, newWatchName, movementRef) — id null creates a new watch. */
    val onSaveResult: (String?, String?, String?) -> Unit,
    val onShowWatchLog: (Boolean) -> Unit,
    /** (watchId, name, movementRef) — fixes a mistyped movement label. */
    val onUpdateWatch: (String, String, String) -> Unit,
    val onDeleteWatch: (String) -> Unit,
)

/** Binds every screen intent to its [viewModel] handler. */
fun captureActions(viewModel: TimegrapherViewModel) = CaptureActions(
    onToggleCapture = viewModel::toggleCapture,
    onDismissInputLost = viewModel::dismissInputLost,
    onSetBphOverride = viewModel::setBphOverride,
    onSetGateTrim = viewModel::setGateTrimDb,
    onRecalibrate = viewModel::recalibrateGate,
    onSetInputPreference = viewModel::setInputPreference,
    onSetAnalysisMode = viewModel::setAnalysisMode,
    onDismissOnboarding = viewModel::dismissOnboarding,
    onReplayFile = viewModel::replayFile,
    onRunDemo = viewModel::runDemo,
    onExportSession = viewModel::exportSession,
    onExportHandled = viewModel::onExportHandled,
    onRecordDiagnostic = viewModel::recordDiagnostic,
    onAdjustClockCal = viewModel::adjustClockCal,
    watchLog = WatchLogActions(
        onOpenSaveDialog = viewModel::openSaveDialog,
        onDismissSaveDialog = viewModel::dismissSaveDialog,
        onSaveResult = viewModel::saveResult,
        onShowWatchLog = viewModel::setShowWatchLog,
        onUpdateWatch = viewModel::updateWatch,
        onDeleteWatch = viewModel::deleteWatch,
    ),
)
