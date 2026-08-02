package com.deadaccurate.app.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceComputerTest {

    private companion object {
        const val SAMPLE_RATE = 48000
        const val BPH = 28800
        const val PERIOD_FRAMES = 6000f // 125 ms
    }

    @Test
    fun perfectWatchDrawsAHorizontalLine() {
        val computer = TraceComputer(SAMPLE_RATE, BPH)
        val first = computer.addTick(0f)
        repeat(20) {
            assertEquals(first, computer.addTick(PERIOD_FRAMES), 1e-3f)
        }
    }

    @Test
    fun fastWatchDriftsSteadily() {
        val computer = TraceComputer(SAMPLE_RATE, BPH)
        // 6 frames short per beat = 0.125 ms early each beat.
        computer.addTick(0f)
        var previous = 0f
        var totalDrift = 0f
        repeat(10) {
            val deviation = computer.addTick(PERIOD_FRAMES - 6f)
            if (it > 0) {
                totalDrift += deviation - previous
            }
            previous = deviation
        }
        assertEquals(-0.125f * 9, totalDrift, 1e-3f)
    }

    @Test
    fun deviationWrapsInsteadOfGrowing() {
        val computer = TraceComputer(SAMPLE_RATE, BPH)
        computer.addTick(0f)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        // Large rate error for hundreds of beats: the deviation must stay
        // bounded by +/- half a period (62.5 ms).
        repeat(400) {
            val deviation = computer.addTick(PERIOD_FRAMES - 30f)
            min = minOf(min, deviation)
            max = maxOf(max, deviation)
        }
        assertTrue(min >= -computer.halfPeriodMs)
        assertTrue(max < computer.halfPeriodMs)
        assertTrue(max - min > computer.halfPeriodMs) // it did wrap
    }

    @Test
    fun halfPeriodMatchesBeatRate() {
        assertEquals(62.5f, TraceComputer(SAMPLE_RATE, BPH).halfPeriodMs, 1e-3f)
        assertEquals(50f, TraceComputer(SAMPLE_RATE, 36000).halfPeriodMs, 1e-3f)
    }
}
