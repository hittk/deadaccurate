package com.deadaccurate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * Horizontal signal meter for the capture path. Range spans the noise floor
 * to full scale; the thin marker is the hop peak. The gate threshold overlay
 * (FR-3) is added in M2 when the gate exists.
 */
@Composable
fun LevelMeter(
    rmsDb: Float,
    peakDb: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val peakColor = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier.fillMaxWidth().height(32.dp)) {
        val corner = CornerRadius(size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = corner)

        val rmsFraction = dbToFraction(rmsDb)
        if (rmsFraction > 0f) {
            drawRoundRect(
                color = fillColor,
                size = Size(size.width * rmsFraction, size.height),
                cornerRadius = corner,
            )
        }

        val peakFraction = dbToFraction(peakDb)
        if (peakFraction > 0f) {
            val x = size.width * peakFraction
            drawLine(
                color = peakColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = PEAK_MARKER_WIDTH_PX,
            )
        }
    }
}

private const val METER_MIN_DB = -90f
private const val METER_MAX_DB = 0f
private const val PEAK_MARKER_WIDTH_PX = 4f

private fun dbToFraction(db: Float): Float =
    ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
