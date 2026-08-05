package com.deadaccurate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.deadaccurate.app.ui.theme.Gold
import com.deadaccurate.app.watchlog.Measurement
import com.deadaccurate.app.watchlog.WatchEntry
import java.text.DateFormat
import java.util.Date

/**
 * The Watch Log tab: every watch the user tracks, its movement, and its
 * numbers over time. **By watch** shows rich per-watch cards (tap one for
 * the complete history with a rate trend line); **All readings** is the
 * flat list of every saved measurement, newest first.
 */
@Composable
fun WatchLogScreen(
    watches: List<WatchEntry>,
    onUpdateWatch: (String, String, String) -> Unit,
    onDeleteWatch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var allReadingsTab by rememberSaveable { mutableStateOf(false) }
    var detailWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var editWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailWatch = watches.find { it.id == detailWatchId }
    val editWatch = watches.find { it.id == editWatchId }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (detailWatch != null) {
            WatchDetail(
                watch = detailWatch,
                onBack = { detailWatchId = null },
                onEdit = { editWatchId = detailWatch.id },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !allReadingsTab,
                    onClick = { allReadingsTab = false },
                    label = { Text("By watch") },
                )
                FilterChip(
                    selected = allReadingsTab,
                    onClick = { allReadingsTab = true },
                    label = { Text("All readings") },
                )
            }
            when {
                watches.isEmpty() -> EmptyLog()
                allReadingsTab -> AllReadingsList(watches)
                else -> watches.forEach { watch ->
                    WatchCard(
                        watch = watch,
                        onOpen = { detailWatchId = watch.id },
                        onEdit = { editWatchId = watch.id },
                        onDelete = { onDeleteWatch(watch.id) },
                    )
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
private fun EmptyLog() {
    Text(
        "No saved measurements yet. Stop a test with a valid reading and " +
            "save it to start tracking a watch over time.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

/** One watch: identity, latest numbers, and its rate trend at a glance. */
@Composable
private fun WatchCard(
    watch: WatchEntry,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(watch.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            watch.movementRef?.let { "Movement: $it" }
                                ?: "Movement not set",
                            "${watch.measurements.size} readings",
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RateSparkline(
                    values = watch.measurements.map { it.secPerDay }.reversed(),
                    modifier = Modifier.width(96.dp).height(40.dp),
                )
            }
            watch.measurements.firstOrNull()?.let { last ->
                Text(
                    "Last: ${formatReading(last)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Gold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row {
                TextButton(onClick = onOpen) { Text("History") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** Complete reading history of one watch, with the rate trend up top. */
@Composable
private fun WatchDetail(
    watch: WatchEntry,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text(
            watch.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit) { Text("Edit") }
    }
    Text(
        watch.movementRef?.let { "Movement: $it" } ?: "Movement not set",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (watch.measurements.size >= 2) {
        PanelCard(label = "RATE TREND") {
            RateSparkline(
                values = watch.measurements.map { it.secPerDay }.reversed(),
                modifier = Modifier.fillMaxWidth().height(80.dp),
                showEndpoints = true,
            )
        }
    }
    watch.measurements.forEach { m ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatDate(m.timestampMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatReading(m), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
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
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "${formatDate(m.timestampMs)} • ${watch.name}" +
                    (watch.movementRef?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatReading(m), style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Gold trend line of a watch's rate history (oldest to newest). */
@Composable
private fun RateSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    showEndpoints: Boolean = false,
) {
    if (values.size < 2) {
        Box(modifier)
        return
    }
    val lineColor = Gold
    val zeroColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val min = values.min()
        val max = values.max()
        val span = (max - min).coerceAtLeast(0.2f)
        val pad = size.height * 0.15f
        val usable = size.height - 2 * pad
        fun yFor(v: Float) = pad + (1f - (v - min) / span) * usable
        // Zero line, when zero is within view.
        if (min <= 0f && max >= 0f) {
            val zy = yFor(0f)
            drawLine(zeroColor, Offset(0f, zy), Offset(size.width, zy), strokeWidth = 1f)
        }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val point = Offset(i * stepX, yFor(v))
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
        if (showEndpoints) {
            drawCircle(lineColor, radius = 5f, center = Offset(size.width, yFor(values.last())))
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
