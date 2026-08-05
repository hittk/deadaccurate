package com.deadaccurate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.deadaccurate.app.PendingResult
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.watchlog.WatchEntry

/**
 * The finish-measurement row under the readout, plus the dialogs it opens:
 * the save-result popup (auto-opened when the user stops a test that has a
 * valid reading) and the watch-log browser. A live "Sounds like…" line
 * shows the movement guess while the test is still running.
 */
@Composable
internal fun ResultActions(state: TimegrapherUiState, watchLog: WatchLogActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = watchLog.onOpenSaveDialog,
            enabled = state.rateValid,
        ) {
            Text(if (state.measurementSettled) "Save result ✓" else "Save result")
        }
        TextButton(onClick = { watchLog.onShowWatchLog(true) }) { Text("Watch log") }
    }
    // Live recognition while the test runs; the popup itself waits for Stop.
    state.movementGuess?.let { guess ->
        Text(
            "Sounds like a ${guess.movementRef}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (state.measurementSettled && !state.showSaveDialog) {
        Text(
            "Reading settled — stop the test to save the result.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    state.pendingResult?.takeIf { state.showSaveDialog }?.let { result ->
        SaveResultDialog(
            result = result,
            watches = state.watches,
            onSave = watchLog.onSaveResult,
            onDismiss = watchLog.onDismissSaveDialog,
        )
    }
    if (state.showWatchLog) {
        WatchLogBrowser(
            watches = state.watches,
            onUpdateWatch = watchLog.onUpdateWatch,
            onDeleteWatch = watchLog.onDeleteWatch,
            onDismiss = { watchLog.onShowWatchLog(false) },
        )
    }
}

/**
 * "Measurement finished" popup: the numbers, the movement guess, and the
 * choice of which watch to file the result under. Saving with a movement
 * reference is what teaches the recognizer.
 */
@Composable
fun SaveResultDialog(
    result: PendingResult,
    watches: List<WatchEntry>,
    onSave: (watchId: String?, newWatchName: String?, movementRef: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = "New watch" selected.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var movementRef by rememberSaveable { mutableStateOf(result.guess?.movementRef ?: "") }
    val selected = watches.find { it.id == selectedId }

    // A plain Dialog, not AlertDialog: text fields inside AlertDialog's
    // intrinsically-measured content can drive an infinite measure loop.
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Measurement result", style = MaterialTheme.typography.titleLarge)
                ResultSummary(result)
                Spacer(Modifier.height(4.dp))
                Text("Save to watch:", style = MaterialTheme.typography.labelLarge)
                WatchPickerChips(
                    watches = watches,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                )
                if (selected == null) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Watch name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = movementRef,
                    onValueChange = { movementRef = it },
                    label = { Text("Movement (e.g. NH35)") },
                    singleLine = true,
                    supportingText = {
                        Text("Labeling the movement teaches the app its sound")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Not now") }
                    TextButton(
                        onClick = { onSave(selectedId, newName, movementRef) },
                        enabled = selected != null || newName.isNotBlank(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
internal fun ResultSummary(result: PendingResult) {
    Text(
        "%+.1f s/day".format(result.secPerDay),
        style = MaterialTheme.typography.headlineMedium,
    )
    val beatError = result.beatErrorMs?.let { "beat error %.1f ms".format(it) }
        ?: "beat error not measured"
    Text(
        "${result.bph} bph • $beatError",
        style = MaterialTheme.typography.bodyMedium,
    )
    result.guess?.let { guess ->
        Text(
            "Sounds like a ${guess.movementRef} (heard on “${guess.watchName}”)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun WatchPickerChips(
    watches: List<WatchEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedId == null,
                onClick = { onSelect(null) },
                label = { Text("New watch") },
            )
        }
        watches.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { watch ->
                    FilterChip(
                        selected = selectedId == watch.id,
                        onClick = { onSelect(watch.id) },
                        label = { Text(watch.name) },
                    )
                }
            }
        }
    }
}

