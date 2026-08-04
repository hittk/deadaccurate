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

/**
 * Detection-path switch: edge detection for a wired piezo's strong signal,
 * energy folding (correlation) for the built-in microphone.
 */
@Composable
fun AnalysisModeSelector(
    mode: com.deadaccurate.app.settings.AnalysisMode,
    onSelect: (com.deadaccurate.app.settings.AnalysisMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = mode == com.deadaccurate.app.settings.AnalysisMode.EDGE,
            onClick = { onSelect(com.deadaccurate.app.settings.AnalysisMode.EDGE) },
            label = { Text("Piezo · edge") },
        )
        FilterChip(
            selected = mode == com.deadaccurate.app.settings.AnalysisMode.CORRELATION,
            onClick = { onSelect(com.deadaccurate.app.settings.AnalysisMode.CORRELATION) },
            label = { Text("Phone mic · correlation") },
        )
    }
}

/** Beat-rate control (FR-4): auto-detection by default, tap to pin. */
@Composable
fun RateSelector(
    overrideBph: Int?,
    detectedBph: Int,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val autoLabel =
            if (overrideBph == null && detectedBph > 0) "Auto ($detectedBph)" else "Auto"
        FilterChip(
            selected = overrideBph == null,
            onClick = { onSelect(null) },
            label = { Text(autoLabel) },
        )
        TimegrapherUiState.STANDARD_RATES.forEach { bph ->
            FilterChip(
                selected = bph == overrideBph,
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

/**
 * Clock calibration (docs/03-signal-processing.md §7): a stored s/day
 * offset measured once against a reference timegrapher or known-rate
 * movement. Steppers rather than a slider — the value wants 0.1 precision.
 */
@Composable
fun CalibrationControls(
    clockCalSecPerDay: Float,
    onAdjust: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Clock cal %+.1f s/d".format(clockCalSecPerDay),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { onAdjust(-CAL_STEP) }) { Text("−0.1") }
        TextButton(onClick = { onAdjust(CAL_STEP) }) { Text("+0.1") }
        if (clockCalSecPerDay != 0f) {
            TextButton(onClick = { onAdjust(0f) }) { Text("Reset") }
        }
    }
}

private const val CAL_STEP = 0.1f
