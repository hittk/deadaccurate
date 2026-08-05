package com.deadaccurate.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.deadaccurate.app.watchlog.Measurement
import com.deadaccurate.app.watchlog.WatchEntry
import java.text.DateFormat
import java.util.Date

/**
 * The watch log, two ways in: **By watch** (tap one for its complete
 * history, edit a mistyped movement label, delete) and **All readings**
 * (every saved measurement across all watches, newest first).
 */
@Composable
fun WatchLogBrowser(
    watches: List<WatchEntry>,
    onUpdateWatch: (String, String, String) -> Unit,
    onDeleteWatch: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var allReadingsTab by rememberSaveable { mutableStateOf(false) }
    var detailWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var editWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailWatch = watches.find { it.id == detailWatchId }
    val editWatch = watches.find { it.id == editWatchId }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    detailWatch?.name ?: "Watch log",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (detailWatch == null) {
                    BrowserTabs(allReadingsTab) { allReadingsTab = it }
                }
                BrowserContent(
                    watches = watches,
                    detailWatch = detailWatch,
                    allReadingsTab = allReadingsTab,
                    onOpen = { detailWatchId = it },
                    onEdit = { editWatchId = it },
                    onDelete = onDeleteWatch,
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (detailWatch != null) {
                        TextButton(onClick = { detailWatchId = null }) { Text("Back") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }

    editWatch?.let { watch ->
        EditWatchDialog(
            watch = watch,
            onSave = { name, ref ->
                onUpdateWatch(watch.id, name, ref)
                editWatchId = null
            },
            onDismiss = { editWatchId = null },
        )
    }
}

@Composable
private fun BrowserTabs(allReadingsTab: Boolean, onSelect: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !allReadingsTab,
            onClick = { onSelect(false) },
            label = { Text("By watch") },
        )
        FilterChip(
            selected = allReadingsTab,
            onClick = { onSelect(true) },
            label = { Text("All readings") },
        )
    }
}

@Composable
private fun BrowserContent(
    watches: List<WatchEntry>,
    detailWatch: WatchEntry?,
    allReadingsTab: Boolean,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            watches.isEmpty() -> Text(
                "No saved measurements yet. Stop a test with a valid " +
                    "reading and save it to start tracking a watch over time.",
                style = MaterialTheme.typography.bodyMedium,
            )
            detailWatch != null -> WatchDetail(detailWatch)
            allReadingsTab -> AllReadingsList(watches)
            else -> watches.forEach { watch ->
                WatchSummaryRow(
                    watch = watch,
                    onOpen = { onOpen(watch.id) },
                    onEdit = { onEdit(watch.id) },
                    onDelete = { onDelete(watch.id) },
                )
            }
        }
    }
}

@Composable
private fun WatchSummaryRow(
    watch: WatchEntry,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Text(watch.name, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                watch.movementRef?.let { "Movement: $it" } ?: "Movement not set",
                "${watch.measurements.size} readings",
            ).joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        watch.measurements.firstOrNull()?.let { last ->
            Text(
                "Last: ${formatReading(last)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row {
            TextButton(onClick = onOpen) { Text("History") }
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
        HorizontalDivider()
    }
}

/** Complete reading history of one watch, newest first. */
@Composable
private fun WatchDetail(watch: WatchEntry) {
    Text(
        watch.movementRef?.let { "Movement: $it" } ?: "Movement not set",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    watch.measurements.forEach { m ->
        Column {
            Text(formatDate(m.timestampMs), style = MaterialTheme.typography.bodySmall)
            Text(formatReading(m), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Every saved reading across all watches, newest first. */
@Composable
private fun AllReadingsList(watches: List<WatchEntry>) {
    val readings = watches
        .flatMap { watch -> watch.measurements.map { watch to it } }
        .sortedByDescending { (_, m) -> m.timestampMs }
    readings.forEach { (watch, m) ->
        Column {
            Text(
                "${formatDate(m.timestampMs)} • ${watch.name}" +
                    (watch.movementRef?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatReading(m), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Fix a mistyped name or movement label (e.g. NH35 typed for an NH34). */
@Composable
private fun EditWatchDialog(
    watch: WatchEntry,
    onSave: (name: String, movementRef: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(watch.name) }
    var movementRef by rememberSaveable { mutableStateOf(watch.movementRef ?: "") }
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Edit watch", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Watch name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = movementRef,
                    onValueChange = { movementRef = it },
                    label = { Text("Movement (e.g. NH34)") },
                    singleLine = true,
                    supportingText = {
                        Text("Correcting this also corrects what the recognizer learns")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onSave(name, movementRef) },
                        enabled = name.isNotBlank(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

private fun formatReading(m: Measurement): String {
    val beatError = m.beatErrorMs?.let { " • %.1f ms beat error".format(it) } ?: ""
    return "%+.1f s/d".format(m.secPerDay) + beatError + " • ${m.bph} bph"
}

private fun formatDate(timestampMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(timestampMs))
