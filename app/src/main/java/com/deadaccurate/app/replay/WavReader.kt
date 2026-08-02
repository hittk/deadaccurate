package com.deadaccurate.app.replay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE parser for the replay path: 16-bit PCM or 32-bit float,
 * mono or stereo (stereo is averaged down). Anything else fails with a
 * message the UI can show.
 */
object WavReader {

    class Wav(val sampleRate: Int, val samples: FloatArray)

    class UnsupportedWavException(message: String) : Exception(message)

    @Suppress("ThrowsCount")
    fun parse(bytes: ByteArray): Wav {
        if (bytes.size < HEADER_BYTES ||
            !hasTag(bytes, 0, "RIFF") || !hasTag(bytes, WAVE_TAG_OFFSET, "WAVE")
        ) {
            throw UnsupportedWavException("Not a WAV file")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var fmt: Format? = null
        var samples: FloatArray? = null

        var position = HEADER_BYTES
        while (position + CHUNK_HEADER_BYTES <= bytes.size) {
            val id = String(bytes, position, TAG_BYTES, Charsets.US_ASCII)
            val size = buffer.getInt(position + TAG_BYTES)
            val body = position + CHUNK_HEADER_BYTES
            if (size < 0 || body + size > bytes.size) break

            when (id) {
                "fmt " -> fmt = Format(
                    format = buffer.getShort(body).toInt() and UNSIGNED_SHORT_MASK,
                    channels = buffer.getShort(body + CHANNELS_OFFSET).toInt(),
                    sampleRate = buffer.getInt(body + SAMPLE_RATE_OFFSET),
                    bitsPerSample = buffer.getShort(body + BITS_OFFSET).toInt(),
                )
                "data" -> {
                    val format = fmt
                        ?: throw UnsupportedWavException("WAV data before fmt chunk")
                    samples = decode(buffer, body, size, format)
                }
            }
            // Chunks are word-aligned: odd sizes carry a pad byte.
            position = body + size + (size and 1)
        }

        val decoded = samples ?: throw UnsupportedWavException("WAV file has no audio data")
        val format = checkNotNull(fmt)
        if (format.sampleRate <= 0) throw UnsupportedWavException("Invalid sample rate")
        return Wav(format.sampleRate, decoded)
    }

    private class Format(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private fun decode(buffer: ByteBuffer, offset: Int, size: Int, fmt: Format): FloatArray {
        if (fmt.channels !in 1..2) {
            throw UnsupportedWavException("Only mono or stereo WAV is supported")
        }
        val channels = fmt.channels
        return when {
            fmt.format == FORMAT_PCM && fmt.bitsPerSample == BITS_PCM16 -> {
                val frames = size / BYTES_PER_PCM16 / channels
                FloatArray(frames) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val index = offset + (frame * channels + channel) * BYTES_PER_PCM16
                        sum += buffer.getShort(index) / PCM16_SCALE
                    }
                    sum / channels
                }
            }
            fmt.format == FORMAT_IEEE_FLOAT && fmt.bitsPerSample == BITS_FLOAT32 -> {
                val frames = size / BYTES_PER_FLOAT32 / channels
                FloatArray(frames) { frame ->
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val index = offset + (frame * channels + channel) * BYTES_PER_FLOAT32
                        sum += buffer.getFloat(index)
                    }
                    sum / channels
                }
            }
            else -> throw UnsupportedWavException(
                "Unsupported WAV encoding (need 16-bit PCM or 32-bit float, " +
                    "got format ${fmt.format} at ${fmt.bitsPerSample} bits)",
            )
        }
    }

    private fun hasTag(bytes: ByteArray, offset: Int, tag: String): Boolean =
        String(bytes, offset, TAG_BYTES, Charsets.US_ASCII) == tag

    private const val TAG_BYTES = 4
    private const val HEADER_BYTES = 12
    private const val WAVE_TAG_OFFSET = 8
    private const val CHUNK_HEADER_BYTES = 8
    private const val CHANNELS_OFFSET = 2
    private const val SAMPLE_RATE_OFFSET = 4
    private const val BITS_OFFSET = 14
    private const val UNSIGNED_SHORT_MASK = 0xFFFF
    private const val FORMAT_PCM = 1
    private const val FORMAT_IEEE_FLOAT = 3
    private const val BITS_PCM16 = 16
    private const val BITS_FLOAT32 = 32
    private const val BYTES_PER_PCM16 = 2
    private const val BYTES_PER_FLOAT32 = 4
    private const val PCM16_SCALE = 32768f
}
