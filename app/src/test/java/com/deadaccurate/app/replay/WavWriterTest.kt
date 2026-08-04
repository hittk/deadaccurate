package com.deadaccurate.app.replay

import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {

    @Test
    fun writerOutputRoundTripsThroughTheReader() {
        val samples = shortArrayOf(0, 16384, -32768, 32767)
        val wav = WavReader.parse(WavWriter.toWavBytes(samples, 48000))

        assertEquals(48000, wav.sampleRate)
        assertEquals(4, wav.samples.size)
        assertEquals(0f, wav.samples[0], 1e-6f)
        assertEquals(0.5f, wav.samples[1], 1e-6f)
        assertEquals(-1f, wav.samples[2], 1e-6f)
        assertEquals(32767f / 32768f, wav.samples[3], 1e-6f)
    }
}
