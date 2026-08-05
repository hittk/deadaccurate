package com.deadaccurate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deadaccurate.app.TimegrapherUiState

/**
 * First-run hardware guidance and the honest accuracy disclosure
 * (docs/03-signal-processing.md §7). Dismissal persists (FR-7).
 */
@Composable
fun OnboardingCard(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Getting a clean signal",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "• Best results come from a piezo contact disc wired to a " +
                    "TRRS (CTIA) headset plug — rest the watch case directly " +
                    "on the disc.\n" +
                    "• USB-C to 3.5mm adapters must contain a microphone " +
                    "input (ADC); many audio-out-only dongles don't.\n" +
                    "• The built-in microphone works for loud movements in a " +
                    "quiet room.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Accuracy note: the absolute rate is limited by this " +
                    "device's audio clock, typically within ±1–3 s/day. " +
                    "Relative measurements — regulating a movement toward " +
                    "zero or comparing positions — are unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}

/** Session alerts: input loss, gate trouble, capture and replay errors. */
@Composable
fun Notices(state: TimegrapherUiState, onDismissInputLost: () -> Unit) {
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
