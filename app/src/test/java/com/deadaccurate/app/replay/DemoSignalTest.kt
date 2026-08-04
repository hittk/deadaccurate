package com.deadaccurate.app.replay

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSignalTest {

    @Test
    fun generatesFortyFiveSecondsAtFullScaleHeadroom() {
        val samples = DemoSignal.generate()
        assertEquals(45 * DemoSignal.SAMPLE_RATE, samples.size)
        assertTrue(samples.all { abs(it) <= 1f })
    }

    @Test
    fun isDeterministic() {
        assertArrayEquals(DemoSignal.generate(), DemoSignal.generate(), 0f)
    }

    @Test
    fun ticksRiseWellAboveTheNoiseFloor() {
        val samples = DemoSignal.generate()
        // Peak inside the first burst window vs. peak of a between-tick
        // stretch (samples 2000..4000 sit between beats).
        val burstPeak = (0 until 200).maxOf { abs(samples[it]) }
        val noisePeak = (2000 until 4000).maxOf { abs(samples[it]) }
        assertTrue(burstPeak > 50 * noisePeak)
    }

    @Test
    fun beatIntervalsAlternateAroundThePeriod() {
        val samples = DemoSignal.generate()
        // Locate burst onsets by threshold crossing with a refractory gap.
        val onsets = mutableListOf<Int>()
        var i = 0
        while (i < samples.size) {
            if (abs(samples[i]) > 0.05f) {
                onsets.add(i)
                i += 3000 // skip past this burst
            } else {
                i++
            }
        }
        assertTrue(onsets.size > 100)
        val intervals = onsets.zipWithNext { a, b -> b - a }
        // 1.5 ms beat error at 48 kHz = 72 frames: consecutive intervals
        // should differ by ~144 frames, alternating sign.
        val diffs = intervals.zipWithNext { a, b -> b - a }
        val alternating = diffs.zipWithNext { a, b -> a * b < 0 }
        assertTrue(alternating.count { it } > alternating.size * 9 / 10)
    }
}
