package com.deadaccurate.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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

    val actions = captureActions(viewModel)

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

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
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
        Text(
            "DEADACCURATE",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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
            .background(MaterialTheme.colorScheme.background),
    ) {
        BrandHeader(
            capturing = state.capturing,
            startEnabled = true,
            onToggleCapture = actions.onToggleCapture,
        )
        NavTabs(
            selected = if (state.showWatchLog) AppTab.WATCH_LOG else AppTab.MEASURE,
            onSelect = { actions.watchLog.onShowWatchLog(it == AppTab.WATCH_LOG) },
        )
        if (state.showWatchLog) {
            WatchLogScreen(
                watches = state.watches,
                onUpdateWatch = actions.watchLog.onUpdateWatch,
                onDeleteWatch = actions.watchLog.onDeleteWatch,
            )
        } else {
            MeasureContent(state, actions)
        }
    }
    // Hosted at screen level so stopping the test surfaces the popup no
    // matter which tab is showing.
    state.pendingResult?.takeIf { state.showSaveDialog }?.let { result ->
        SaveResultDialog(
            result = result,
            watches = state.watches,
            onSave = actions.watchLog.onSaveResult,
            onDismiss = actions.watchLog.onDismissSaveDialog,
        )
    }
}

@Composable
private fun MeasureContent(state: TimegrapherUiState, actions: CaptureActions) {
    StatusStrip(state)
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ResultActions(state, actions.watchLog)
        Notices(state, actions.onDismissInputLost)
        if (!state.onboardingDismissed) {
            OnboardingCard(onDismiss = actions.onDismissOnboarding)
        }
        StatCardsGrid(state)
        TracePanel(state)
        SignalPanel(state)

        SectionLabel("BEAT RATE")
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

        SectionLabel("ANALYSIS MODE")
        AnalysisModeSelector(mode = state.analysisMode, onSelect = actions.onSetAnalysisMode)

        AdvancedSection(state, actions)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AdvancedSection(state: TimegrapherUiState, actions: CaptureActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            if (expanded) "ADVANCED ▲" else "ADVANCED ▼",
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (!expanded) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        InputSelector(state, actions.onSetInputPreference)
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
        SecondaryActions(
            hasSession = state.tracePoints.isNotEmpty(),
            recordingSecondsLeft = state.recordingSecondsLeft,
            actions = actions,
        )
        StreamInfoFooter(state)
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
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
