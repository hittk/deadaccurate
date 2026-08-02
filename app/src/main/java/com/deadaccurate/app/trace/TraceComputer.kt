package com.deadaccurate.app.trace

/**
 * Maps tick timestamps onto the classic timegrapher "paper tape": each
 * tick's phase within the ideal beat period, wrapped to ±half a period. A
 * healthy watch draws a horizontal line; a fast or slow one draws a slope
 * that wraps top-to-bottom (docs/01-requirements.md FR-6).
 *
 * All time is in audio-clock frames, accumulated as Double (exact for any
 * realistic session length).
 */
class TraceComputer(sampleRate: Int, bph: Int) {
    private val beatPeriodFrames: Double = SECONDS_PER_HOUR / bph * sampleRate
    private val framesPerMs: Double = sampleRate / MS_PER_SECOND

    private var timestampFrames: Double = 0.0
    private var started = false

    /**
     * Consumes the frame delta of the next tick and returns its phase
     * deviation in milliseconds, in [-period/2, period/2).
     */
    fun addTick(deltaFrames: Float): Float {
        if (started) {
            timestampFrames += deltaFrames.toDouble()
        } else {
            started = true
        }
        var phase = timestampFrames.mod(beatPeriodFrames)
        if (phase >= beatPeriodFrames / 2.0) {
            phase -= beatPeriodFrames
        }
        return (phase / framesPerMs).toFloat()
    }

    /** Half the beat period in ms — the trace's vertical half-range. */
    val halfPeriodMs: Float = (beatPeriodFrames / 2.0 / framesPerMs).toFloat()

    private companion object {
        const val SECONDS_PER_HOUR = 3600.0
        const val MS_PER_SECOND = 1000.0
    }
}
