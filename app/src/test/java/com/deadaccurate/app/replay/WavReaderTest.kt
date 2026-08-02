package com.deadaccurate.app.replay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WavReaderTest {

    private fun wavBytes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        format: Int,
        dataBytes: ByteArray,
    ): ByteArray {
        val fmtSize = 16
        val total = 12 + 8 + fmtSize + 8 + dataBytes.size
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(total - 8).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(fmtSize)
        buffer.putShort(format.toShort()).putShort(channels.toShort())
        buffer.putInt(sampleRate)
        val blockAlign = channels * bitsPerSample / 8
        buffer.putInt(sampleRate * blockAlign).putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray()).putInt(dataBytes.size).put(dataBytes)
        return buffer.array()
    }

    @Test
    fun parsesMono16BitPcm() {
        val data = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(16384).putShort((-32768).toShort()).array()
        val wav = WavReader.parse(wavBytes(48000, 1, 16, 1, data))

        assertEquals(48000, wav.sampleRate)
        assertEquals(3, wav.samples.size)
        assertEquals(0f, wav.samples[0], 1e-6f)
        assertEquals(0.5f, wav.samples[1], 1e-6f)
        assertEquals(-1f, wav.samples[2], 1e-6f)
    }

    @Test
    fun downmixesStereoToMono() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16384).putShort(0)          // frame 0: L=0.5, R=0
            .putShort(16384).putShort(16384)      // frame 1: L=R=0.5
            .array()
        val wav = WavReader.parse(wavBytes(44100, 2, 16, 1, data))

        assertEquals(2, wav.samples.size)
        assertEquals(0.25f, wav.samples[0], 1e-6f)
        assertEquals(0.5f, wav.samples[1], 1e-6f)
    }

    @Test
    fun parses32BitFloat() {
        val data = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f).putFloat(-0.75f).array()
        val wav = WavReader.parse(wavBytes(96000, 1, 32, 3, data))

        assertEquals(96000, wav.sampleRate)
        assertEquals(0.25f, wav.samples[0], 1e-6f)
        assertEquals(-0.75f, wav.samples[1], 1e-6f)
    }

    @Test
    fun rejectsNonWavBytes() {
        assertThrows(WavReader.UnsupportedWavException::class.java) {
            WavReader.parse(ByteArray(64) { it.toByte() })
        }
    }

    @Test
    fun rejectsUnsupportedBitDepth() {
        val data = ByteArray(4)
        assertThrows(WavReader.UnsupportedWavException::class.java) {
            WavReader.parse(wavBytes(48000, 1, 8, 1, data))
        }
    }

    @Test
    fun rejectsMissingDataChunk() {
        val bytes = wavBytes(48000, 1, 16, 1, ByteArray(0))
        // Truncate away the (empty) data chunk header.
        val truncated = bytes.copyOf(bytes.size - 8)
        assertThrows(WavReader.UnsupportedWavException::class.java) {
            WavReader.parse(truncated)
        }
    }
}
