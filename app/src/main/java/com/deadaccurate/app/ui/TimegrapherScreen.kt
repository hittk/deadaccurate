package com.deadaccurate.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.TimegrapherViewModel
import com.deadaccurate.app.settings.InputPreference

@Composable
fun TimegrapherScreen(viewModel: TimegrapherViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onPermissionResult,
    )
    // Checked on every resume so a permission revoked in system settings is
    // noticed (architecture §6).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    val actions = CaptureActions(
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
    )

    // An export or recording becoming ready launches the share sheet once.
    val shareUri = state.exportUri ?: state.recordUri
    val shareMime = if (state.exportUri != null) "text/csv" else "audio/wav"
    shareUri?.let { uri ->
        LaunchedEffect(uri) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = shareMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share"))
            viewModel.onExportHandled()
        }
    }

    Scaffold { innerPadding ->
        if (state.hasPermission) {
            CaptureContent(
                state = state,
                actions = actions,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
internal fun PermissionRationale(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DeadAccurate", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "DeadAccurate listens to your watch through the microphone to " +
                "measure its beat. Audio is analyzed on this device only and " +
                "is never recorded or shared.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow microphone access") }
    }
}

@Composable
internal fun CaptureContent(
    state: TimegrapherUiState,
    actions: CaptureActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DeadAccurate", style = MaterialTheme.typography.headlineMedium)
        InputSelector(state, actions.onSetInputPreference)
        AnalysisModeSelector(mode = state.analysisMode, onSelect = actions.onSetAnalysisMode)
        if (!state.onboardingDismissed) {
            OnboardingCard(onDismiss = actions.onDismissOnboarding)
        }
        Notices(state, actions.onDismissInputLost)

        RateReadout(state)
        BeatTrace(points = state.tracePoints, halfRangeMs = state.traceHalfRangeMs)
        RateSelector(
            overrideBph = state.bphOverride,
            detectedBph = state.detectedBph,
            onSelect = actions.onSetBphOverride,
        )
        if (state.overrideDisagrees) {
            Text(
                "The signal looks like ${state.detectedBph} bph, not the " +
                    "pinned ${state.bphOverride}. Tap Auto to trust the signal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        MeterSection(state)
        GateControls(
            trimDb = state.gateTrimDb,
            calibrating = state.calibrating,
            onTrimChange = actions.onSetGateTrim,
            onRecalibrate = actions.onRecalibrate,
        )
        CalibrationControls(
            clockCalSecPerDay = state.clockCalSecPerDay,
            onAdjust = actions.onAdjustClockCal,
        )

        Button(onClick = actions.onToggleCapture, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.capturing) "Stop" else "Start listening")
        }
        SecondaryActions(
            hasSession = state.tracePoints.isNotEmpty(),
            recordingSecondsLeft = state.recordingSecondsLeft,
            actions = actions,
        )
        StreamInfoFooter(state)
    }
}

@Composable
private fun Notices(state: TimegrapherUiState, onDismissInputLost: () -> Unit) {
    if (state.inputLost) {
        NoticeCard(
            text = "Input lost — the wired microphone was disconnected. " +
                "Reconnect it and start again.",
            actionLabel = "Dismiss",
            onAction = onDismissInputLost,
        )
    }
    if (state.noTicksHint) {
        NoticeCard(
            text = "No ticks detected. Try tapping Recalibrate with the " +
                "watch in place, adjusting the gate trim, or pressing the " +
                "watch more firmly against the microphone.",
        )
    }
    if (!state.unprocessedSupported) {
        NoticeCard(
            text = "This device doesn't support fully unprocessed audio " +
                "input; using the voice-recognition source instead.",
        )
    }
    state.startErrorCode?.let { code ->
        NoticeCard(text = "Couldn't start audio capture (error $code).")
    }
    state.replayError?.let { message ->
        NoticeCard(text = "Recording analysis failed: $message")
    }
}

@Composable
private fun SecondaryActions(
    hasSession: Boolean,
    recordingSecondsLeft: Int?,
    actions: CaptureActions,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(actions.onReplayFile) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TextButton(
            onClick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
        ) {
            Text("Analyze WAV…")
        }
        TextButton(onClick = actions.onRunDemo) { Text("Try a demo") }
        TextButton(onClick = actions.onExportSession, enabled = hasSession) {
            Text("Export CSV")
        }
    }
    TextButton(
        onClick = actions.onRecordDiagnostic,
        enabled = recordingSecondsLeft == null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            recordingSecondsLeft?.let { "Recording… ${it}s (keep the watch on the mic)" }
                ?: "Record 60 s for tuning",
        )
    }
}

@Composable
private fun MeterSection(state: TimegrapherUiState) {
    LevelMeter(
        rmsDb = state.rmsDb,
        peakDb = state.peakDb,
        thresholdDb = state.gateThresholdDb,
        gateOpen = state.gateOpen,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("RMS ${formatDb(state.rmsDb)}", style = MaterialTheme.typography.bodyMedium)
        Text("Peak ${formatDb(state.peakDb)}", style = MaterialTheme.typography.bodyMedium)
        state.gateThresholdDb?.let {
            Text("Gate ${formatDb(it)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StreamInfoFooter(state: TimegrapherUiState) {
    val info = state.streamInfo ?: return
    val text =
        if (state.replayFileName != null) {
            "${info.sampleRate} Hz • replay: ${state.replayFileName}"
        } else {
            listOf(
                "${info.sampleRate} Hz",
                if (info.unprocessed) "unprocessed" else "voice-recognition",
                if (info.exclusiveMode) "exclusive" else "shared",
            ).joinToString(" • ")
        }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InputSelector(
    state: TimegrapherUiState,
    onSetInputPreference: (InputPreference) -> Unit,
) {
    val wired = state.wiredInputName
    val usingBuiltIn =
        state.inputPreference == InputPreference.BUILT_IN || wired == null
    val label = when {
        !usingBuiltIn -> "Input: wired mic ($wired)"
        wired != null -> "Input: built-in microphone"
        else -> "Input: built-in microphone — connect a piezo mic for best results"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (wired != null) {
            TextButton(
                onClick = {
                    onSetInputPreference(
                        if (usingBuiltIn) InputPreference.AUTO else InputPreference.BUILT_IN,
                    )
                },
            ) {
                Text(if (usingBuiltIn) "Use wired" else "Use built-in")
            }
        }
    }
}

@Composable
private fun NoticeCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun formatDb(db: Float): String =
    if (db <= TimegrapherUiState.SILENCE_DB) "—" else "%.1f dB".format(db)
