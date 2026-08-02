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
