package com.deadaccurate.app.replay

import android.content.ContentResolver
import android.net.Uri
import com.deadaccurate.engine.EngineEvent
import com.deadaccurate.engine.ReplayEngine
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a recorded WAV file through the same DSP chain as live capture.
 * Lets the pipeline be exercised with real watch recordings before (or
 * without) a piezo rig, and is the vehicle for real-recording fixtures.
 */
class ReplayAnalyzer(private val contentResolver: ContentResolver) {

    /**
     * Parses [uri], then streams it through a [ReplayEngine]. [onStart]
     * fires with the file's sample rate before any events; [onEvent]
     * receives the same event stream live capture produces. Throws
     * [WavReader.UnsupportedWavException] or [IOException] on bad input.
     */
    suspend fun analyze(
        uri: Uri,
        bphOverride: Int,
        gateTrimDb: Float,
        onStart: (sampleRate: Int) -> Unit,
        onEvent: (EngineEvent) -> Unit,
    ) = withContext(Dispatchers.Default) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Could not open the recording")
        val wav = WavReader.parse(bytes)
        onStart(wav.sampleRate)

        ReplayEngine(wav.sampleRate).use { engine ->
            engine.setBphOverride(bphOverride)
            engine.setGateTrimDb(gateTrimDb)

            val chunk = FloatArray(ReplayEngine.CHUNK_FRAMES)
            var offset = 0
            while (offset < wav.samples.size) {
                val count = minOf(chunk.size, wav.samples.size - offset)
                wav.samples.copyInto(chunk, 0, offset, offset + count)
                engine.process(chunk, count).forEach(onEvent)
                offset += count
            }
        }
    }
}
