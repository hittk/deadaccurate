package com.deadaccurate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deadaccurate.app.TimegrapherUiState
import com.deadaccurate.app.settings.AnalysisMode
import com.deadaccurate.app.ui.theme.Gold
import com.deadaccurate.app.ui.theme.NavyHeader

/** Brand bar: the triangle mark, the wordmark, and the start/stop control. */
@Composable
fun BrandHeader(
    capturing: Boolean,
    startEnabled: Boolean,
    onToggleCapture: () -> Unit,
) {
    Surface(color = NavyHeader) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriangleMark()
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    "DEADACCURATE",
                    style = MaterialTheme.typography.titleLarge,
                    color = Gold,
                )
                Text(
                    "TIMEGRAPHER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StartStopButton(capturing, startEnabled, onToggleCapture)
        }
    }
}

/** The inverted-triangle house mark with the serif D inside. */
@Composable
private fun TriangleMark() {
    Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(46.dp)) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, color = Gold, style = Stroke(width = 3f))
        }
        Text(
            "D",
            color = Gold,
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun StartStopButton(
    capturing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(Gold.copy(alpha = alpha), CircleShape)
            .clickable(enabled = enabled, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) {
            Box(Modifier.size(16.dp).background(NavyHeader, RoundedCornerShape(2.dp)))
        } else {
            Canvas(modifier = Modifier.size(20.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.2f, 0f)
                    lineTo(size.width * 0.2f, size.height)
                    lineTo(size.width, size.height / 2f)
                    close()
                }
                drawPath(path, color = NavyHeader)
            }
        }
    }
}

enum class AppTab { MEASURE, WATCH_LOG }

/** Top-level navigation: the instrument and the log, side by side. */
@Composable
fun NavTabs(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        NavTab("MEASURE", selected == AppTab.MEASURE) { onSelect(AppTab.MEASURE) }
        NavTab("WATCH LOG", selected == AppTab.WATCH_LOG) { onSelect(AppTab.WATCH_LOG) }
    }
}

@Composable
private fun RowScope.NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .height(2.dp)
                .fillMaxWidth(0.6f)
                .background(if (selected) Gold else MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/** The gold status line under the header ("ready", "locked 21600 bph…"). */
@Composable
fun StatusStrip(state: TimegrapherUiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(
            text = statusLine(state),
            style = MaterialTheme.typography.labelLarge,
            color = Gold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** One measurement card: label on top, big serif value, unit underneath. */
@Composable
fun StatCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.displayMedium,
                color = if (emphasized) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The four instrument readouts, arranged as on the face of the design. */
@Composable
fun StatCardsGrid(state: TimegrapherUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "RATE",
                value = if (state.rateValid) {
                    formatSignedValue(state.correctedSecPerDay)
                } else {
                    "—.—"
                },
                unit = "SEC / DAY",
                emphasized = state.rateValid,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "BEAT ERROR",
                value = state.beatErrorMs?.let { "%.1f".format(it) } ?: "—.—",
                unit = "MS",
                emphasized = state.beatErrorMs != null,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                label = "BEAT RATE",
                value = if (state.activeBph > 0) compactBph(state.activeBph) else "—",
                unit = if (state.bphOverride != null) "BPH • PINNED" else "BPH • AUTO",
                emphasized = state.activeBph > 0,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = "AMPLITUDE",
                value = state.amplitudeDeg?.let { "%.0f".format(it) } ?: "—",
                unit = state.liftTimeMs?.let { "DEGREES • %.1f MS LIFT".format(it) }
                    ?: "DEGREES",
                emphasized = state.amplitudeDeg != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Panel chrome shared by the trace and signal sections. */
@Composable
fun PanelCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

/** The main tape, with the idle placeholder from the design. */
@Composable
fun TracePanel(state: TimegrapherUiState) {
    PanelCard(label = "TIMING DEVIATION") {
        Box(contentAlignment = Alignment.Center) {
            BeatTrace(points = state.tracePoints, halfRangeMs = state.traceHalfRangeMs)
            if (state.tracePoints.isEmpty()) {
                Text(
                    if (state.capturing) "LISTENING…" else "PRESS START TO BEGIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Envelope level with the gate threshold overlay, panel-framed. */
@Composable
fun SignalPanel(state: TimegrapherUiState) {
    PanelCard(label = "SIGNAL LEVEL") {
        LevelMeter(
            rmsDb = state.rmsDb,
            peakDb = state.peakDb,
            thresholdDb = state.gateThresholdDb,
            gateOpen = state.gateOpen,
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "RMS ${formatDb(state.rmsDb)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Peak ${formatDb(state.peakDb)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.gateThresholdDb?.let {
                Text(
                    "Gate ${formatDb(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDb(db: Float): String =
    if (db <= TimegrapherUiState.SILENCE_DB) "—" else "%.1f dB".format(db)

private fun statusLine(state: TimegrapherUiState): String {
    val base = when {
        !state.capturing && state.replayFileName == null -> "ready"
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

/** 28800 -> "28.8K", 18000 -> "18K". */
fun compactBph(bph: Int): String =
    if (bph % BPH_PER_K == 0) "${bph / BPH_PER_K}K" else "%.1fK".format(bph / BPH_PER_K_F)

fun formatSignedValue(value: Float): String {
    val sign = if (value >= 0) "+" else "−"
    return "%s%.1f".format(sign, kotlin.math.abs(value))
}

private const val BPH_PER_K = 1000
private const val BPH_PER_K_F = 1000f

private const val SECONDS_PER_HOUR = 3600f

/** Mirrors FoldingAnalyzer::kMinBeatsForPhase. */
private const val MIN_PHASE_BEATS = 125

/** Regression span + points after phase tracking starts, roughly. */
private const val RATE_WINDOW_SECONDS = 12f
