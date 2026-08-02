package com.deadaccurate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.deadaccurate.app.TimegrapherUiState

/**
 * The timegrapher tape (FR-6): one dot per detected tick, x scrolling with
 * tick index, y = phase deviation wrapped to ±[halfRangeMs]. Rendered as a
 * single drawPoints call from an immutable snapshot — no per-dot
 * composables.
 */
@Composable
fun BeatTrace(
    points: List<Float>,
    halfRangeMs: Float,
    modifier: Modifier = Modifier,
) {
    val dotColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val background = MaterialTheme.colorScheme.surfaceContainerLow

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(background, RoundedCornerShape(12.dp)),
    ) {
        val midY = size.height / 2f
        drawLine(
            color = gridColor,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )

        if (points.isEmpty() || halfRangeMs <= 0f) return@Canvas

        val stepX = size.width / (TimegrapherUiState.TRACE_CAPACITY - 1)
        val offsets = points.mapIndexed { index, deviationMs ->
            Offset(
                x = index * stepX,
                y = midY - (deviationMs / halfRangeMs) * midY,
            )
        }
        drawPoints(
            points = offsets,
            pointMode = PointMode.Points,
            color = dotColor,
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
    }
}
