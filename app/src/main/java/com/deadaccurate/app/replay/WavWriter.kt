package com.deadaccurate.app.replay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM mono WAV encoder for the diagnostic recorder. */
object WavWriter {

    fun toWavBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * BYTES_PER_SAMPLE
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(HEADER_BYTES - RIFF_PREFIX_BYTES + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(FMT_CHUNK_BYTES)
        buffer.putShort(FORMAT_PCM)
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * BYTES_PER_SAMPLE)
        buffer.putShort(BYTES_PER_SAMPLE.toShort())
        buffer.putShort(BITS_PER_SAMPLE)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private const val HEADER_BYTES = 44
    private const val RIFF_PREFIX_BYTES = 8
    private const val FMT_CHUNK_BYTES = 16
    private const val FORMAT_PCM: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val BYTES_PER_SAMPLE = 2
}
