package com.deadaccurate.app.replay

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthesizes a demo watch so the instrument can be exercised with zero
 * hardware: a 28800 bph movement running ~7 s/day fast with 1.5 ms of beat
 * error over a ~-60 dBFS noise floor — the same construction as the C++
 * golden-test fixtures. Deterministic (LCG noise), so the analyzed result
 * is repeatable.
 */
object DemoSignal {
    const val SAMPLE_RATE = 48_000
    const val BPH = 28_800
    const val SEC_PER_DAY_FAST = 7.0
    const val BEAT_ERROR_MS = 1.5

    // Long enough for correlation mode to lock, settle, and show a valid
    // rate — it needs ~30 s of settled fold before the readout appears.
    private const val SECONDS = 45
    private const val NOISE_AMPLITUDE = 0.001f
    private const val BURST_AMPLITUDE = 0.3
    private const val BURST_FRAMES = 96 // 2 ms
    private const val BURST_DECAY_FRAMES = 24.0 // 0.5 ms
    private const val BURST_HZ = 5_000.0
    private const val SECONDS_PER_DAY = 86_400.0
    private const val SECONDS_PER_HOUR = 3_600.0
    private const val MS_PER_SECOND = 1_000.0

    // Numerical-recipes LCG; same constants as the C++ test fixtures.
    private const val LCG_SEED = 12345u
    private const val LCG_MULTIPLIER = 1664525u
    private const val LCG_INCREMENT = 1013904223u
    private const val LCG_DISCARD_BITS = 8
    private const val LCG_RANGE = 1 shl 24

    fun generate(): FloatArray {
        val samples = FloatArray(SECONDS * SAMPLE_RATE)

        var lcg = LCG_SEED
        for (i in samples.indices) {
            lcg = lcg * LCG_MULTIPLIER + LCG_INCREMENT
            samples[i] = NOISE_AMPLITUDE *
                (((lcg shr LCG_DISCARD_BITS).toFloat() / LCG_RANGE) * 2f - 1f)
        }

        val idealPeriod = SECONDS_PER_HOUR / BPH * SAMPLE_RATE
        val period = idealPeriod * (1.0 - SEC_PER_DAY_FAST / SECONDS_PER_DAY)
        val beatErrorFrames = (BEAT_ERROR_MS / MS_PER_SECOND * SAMPLE_RATE).toInt()

        var beat = 0
        while (true) {
            // Odd beats land late: intervals alternate T+e / T-e.
            val start = (beat * period).toInt() + if (beat % 2 == 1) beatErrorFrames else 0
            if (start + BURST_FRAMES >= samples.size) break
            for (j in 0 until BURST_FRAMES) {
                samples[start + j] += (
                    BURST_AMPLITUDE * exp(-j / BURST_DECAY_FRAMES) *
                        sin(2.0 * PI * BURST_HZ * j / SAMPLE_RATE)
                    ).toFloat()
            }
            beat++
        }
        return samples
    }
}
