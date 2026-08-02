package com.deadaccurate.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deadaccurate.app.TimegrapherUiState
import kotlin.math.roundToInt

/** Manual beat-rate selection (M2; auto-detect arrives in M3). */
@Composable
fun RateSelector(
    selectedBph: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TimegrapherUiState.STANDARD_RATES.forEach { bph ->
            FilterChip(
                selected = bph == selectedBph,
                onClick = { onSelect(bph) },
                label = { Text("$bph") },
            )
        }
    }
}

/** Gate trim slider and recalibrate action (FR-3). */
@Composable
fun GateControls(
    trimDb: Float,
    calibrating: Boolean,
    onTrimChange: (Float) -> Unit,
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Gate " + formatTrim(trimDb),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRecalibrate, enabled = !calibrating) {
                Text(if (calibrating) "Calibrating…" else "Recalibrate")
            }
        }
        Slider(
            value = trimDb,
            onValueChange = onTrimChange,
            valueRange = -15f..15f,
            steps = 29,
        )
    }
}

private fun formatTrim(trimDb: Float): String {
    val rounded = trimDb.roundToInt()
    return if (rounded >= 0) "+$rounded dB" else "$rounded dB"
}
