package com.deadaccurate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.settings.AnalysisMode

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
            "locked ${state.activeBph} bph • ${state.rateTickCount} ticks" +
                settlingEta(state)
        else -> "searching for beat rate…"
    }
    return if (state.clockCalSecPerDay != 0f) {
        "$base • cal %+.1f s/d".format(state.clockCalSecPerDay)
    } else {
        base
    }
}

/**
 * The wait for the first s/day reading is long by design (the correlation
 * path integrates over beats); an honest countdown beats a number that
 * "takes forever to appear". Mirrors the engine's thresholds: phase points
 * start after [MIN_PHASE_BEATS] (FoldingAnalyzer::kMinBeatsForPhase), then
 * the regression needs ~[RATE_WINDOW_SECONDS] more of span.
 */
private fun settlingEta(state: TimegrapherUiState): String {
    val waiting = !state.rateValid && state.activeBph > 0 &&
        state.analysisMode == AnalysisMode.CORRELATION
    if (!waiting) return ""
    val beatsPerSecond = state.activeBph / SECONDS_PER_HOUR
    val beatsLeft = (MIN_PHASE_BEATS - state.rateTickCount).coerceAtLeast(0)
    val secondsLeft = (beatsLeft / beatsPerSecond + RATE_WINDOW_SECONDS).toInt()
    return " • first reading in ~${secondsLeft}s"
}

private const val SECONDS_PER_HOUR = 3600f

/** Mirrors FoldingAnalyzer::kMinBeatsForPhase. */
private const val MIN_PHASE_BEATS = 125

/** Regression span + points after phase tracking starts, roughly. */
private const val RATE_WINDOW_SECONDS = 12f

private fun formatSecPerDay(secPerDay: Float): String {
    val sign = if (secPerDay >= 0) "+" else "−"
    return "%s%.1f s/d".format(sign, kotlin.math.abs(secPerDay))
}
