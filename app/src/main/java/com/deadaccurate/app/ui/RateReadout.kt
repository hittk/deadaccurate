package com.deadaccurate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.deadaccurate.app.TimegrapherUiState

/**
 * The headline number (FR-5): rate deviation in s/day, greyed until the
 * estimation window is trustworthy, with a status line for the beat-rate
 * detector underneath.
 */
@Composable
fun RateReadout(state: TimegrapherUiState, modifier: Modifier = Modifier) {
    val valueText =
        if (state.rateValid) formatSecPerDay(state.correctedSecPerDay) else "—.— s/d"
    val valueColor =
        if (state.rateValid) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Column(modifier = modifier) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.displayMedium,
            color = valueColor,
        )
        state.beatErrorMs?.let { beatError ->
            Text(
                text = "beat error %.1f ms".format(beatError),
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
            )
        }
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusLine(state: TimegrapherUiState): String {
    val base = when {
        !state.capturing && state.replayFileName == null -> "stopped"
        state.bphOverride != null ->
            "pinned ${state.bphOverride} bph • ${state.rateTickCount} ticks"
        state.activeBph > 0 ->
            "locked ${state.activeBph} bph • ${state.rateTickCount} ticks"
        else -> "searching for beat rate…"
    }
    return if (state.clockCalSecPerDay != 0f) {
        "$base • cal %+.1f s/d".format(state.clockCalSecPerDay)
    } else {
        base
    }
}

private fun formatSecPerDay(secPerDay: Float): String {
    val sign = if (secPerDay >= 0) "+" else "−"
    return "%s%.1f s/d".format(sign, kotlin.math.abs(secPerDay))
}
