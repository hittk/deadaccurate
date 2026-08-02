package com.deadaccurate.app.ui

import android.Manifest
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.TimegrapherViewModel

@Composable
fun TimegrapherScreen(viewModel: TimegrapherViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onPermissionResult,
    )
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(granted)
    }

    Scaffold { innerPadding ->
        if (state.hasPermission) {
            CaptureContent(
                state = state,
                onToggleCapture = viewModel::toggleCapture,
                onDismissInputLost = viewModel::dismissInputLost,
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
private fun PermissionRationale(onRequest: () -> Unit, modifier: Modifier = Modifier) {
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
private fun CaptureContent(
    state: TimegrapherUiState,
    onToggleCapture: () -> Unit,
    onDismissInputLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DeadAccurate", style = MaterialTheme.typography.headlineMedium)

        InputChip(state.wiredInputName)

        if (state.inputLost) {
            NoticeCard(
                text = "Input lost — the wired microphone was disconnected. " +
                    "Reconnect it and start again.",
                actionLabel = "Dismiss",
                onAction = onDismissInputLost,
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

        LevelMeter(rmsDb = state.rmsDb, peakDb = state.peakDb)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "RMS ${formatDb(state.rmsDb)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Peak ${formatDb(state.peakDb)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = onToggleCapture, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.capturing) "Stop" else "Start listening")
        }

        state.streamInfo?.let { info ->
            Text(
                listOfNotNull(
                    "${info.sampleRate} Hz",
                    if (info.unprocessed) "unprocessed" else "voice-recognition",
                    if (info.exclusiveMode) "exclusive" else "shared",
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InputChip(wiredInputName: String?) {
    val label =
        if (wiredInputName != null) {
            "Input: wired mic ($wiredInputName)"
        } else {
            "Input: built-in microphone — connect a piezo mic for best results"
        }
    Text(label, style = MaterialTheme.typography.bodyMedium)
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
