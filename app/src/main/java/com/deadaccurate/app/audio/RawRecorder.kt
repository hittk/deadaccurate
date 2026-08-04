package com.deadaccurate.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostic capture: records exactly what the analysis path would hear
 * (same source preset selection) so real-world recordings can be shared
 * and turned into test fixtures.
 */
class RawRecorder {

    class Recording(val sampleRate: Int, val samples: ShortArray)

    /** Caller guarantees RECORD_AUDIO; the screen is permission-gated. */
    @SuppressLint("MissingPermission")
    suspend fun record(
        seconds: Int,
        unprocessedSupported: Boolean,
        onProgress: (secondsLeft: Int) -> Unit,
    ): Recording = withContext(Dispatchers.IO) {
        val source =
            if (unprocessedSupported) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * BUFFER_FACTOR, SAMPLE_RATE),
        )
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone unavailable"
            }
            val total = seconds * SAMPLE_RATE
            val samples = ShortArray(total)
            record.startRecording()
            var offset = 0
            while (offset < total) {
                val read = record.read(samples, offset, total - offset)
                check(read >= 0) { "Recording failed (error $read)" }
                offset += read
                onProgress(seconds - offset / SAMPLE_RATE)
            }
            record.stop()
            Recording(SAMPLE_RATE, samples)
        } finally {
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val BUFFER_FACTOR = 4
    }
}
