package com.deadaccurate.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExporterTest {

    private val summary = SessionExporter.Summary(
        source = "live",
        bph = 28800,
        rawSecPerDay = 5.9f,
        clockCalSecPerDay = -1.7f,
        beatErrorMs = 0.6f,
        sampleRate = 48000,
    )

    @Test
    fun summaryRowsCarryCorrectedAndRawRate() {
        val csv = SessionExporter.buildCsv(summary, emptyList())
        assertTrue(csv.contains("rate_s_per_day,4.200"))
        assertTrue(csv.contains("rate_raw_s_per_day,5.900"))
        assertTrue(csv.contains("clock_cal_s_per_day,-1.700"))
        assertTrue(csv.contains("beat_error_ms,0.600"))
        assertTrue(csv.contains("beat_rate_bph,28800"))
        assertTrue(csv.contains("ticks,0"))
    }

    @Test
    fun tickRowsAreIndexedAndDotDecimal() {
        val ticks = listOf(
            SessionTick(timeMs = 0.0, deviationMs = 12.345f, accepted = true),
            SessionTick(timeMs = 125.02, deviationMs = -3.5f, accepted = false),
        )
        val csv = SessionExporter.buildCsv(summary, ticks)
        val lines = csv.trim().lines()
        assertEquals("0,0.0000,12.345,1", lines[lines.size - 2])
        assertEquals("1,0.1250,-3.500,0", lines[lines.size - 1])
    }

    @Test
    fun missingBeatErrorLeavesTheFieldEmpty() {
        val csv = SessionExporter.buildCsv(summary.copy(beatErrorMs = null), emptyList())
        assertTrue(csv.contains("beat_error_ms,\n"))
    }
}
