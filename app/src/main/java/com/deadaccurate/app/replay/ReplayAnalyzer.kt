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

    /** Chain settings applied before the offline run. */
    data class Config(
        val bphOverride: Int,
        val gateTrimDb: Float,
        /** [com.deadaccurate.engine.AnalysisModeNative] value. */
        val analysisMode: Int,
        val liftAngleDeg: Float = 52f,
    )

    /**
     * Parses [uri], then streams it through a [ReplayEngine]. [onStart]
     * fires with the file's sample rate before any events; [onEvent]
     * receives the same event stream live capture produces. Throws
     * [WavReader.UnsupportedWavException] or [IOException] on bad input.
     */
    suspend fun analyze(
        uri: Uri,
        config: Config,
        onStart: (sampleRate: Int) -> Unit,
        onEvent: (EngineEvent) -> Unit,
    ) {
        val bytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open the recording")
        }
        val wav = WavReader.parse(bytes)
        analyzeSamples(wav.sampleRate, wav.samples, config, onStart, onEvent)
    }

    /** Same pipeline for in-memory samples (the bundled demo movement). */
    suspend fun analyzeSamples(
        sampleRate: Int,
        samples: FloatArray,
        config: Config,
        onStart: (sampleRate: Int) -> Unit,
        onEvent: (EngineEvent) -> Unit,
    ) = withContext(Dispatchers.Default) {
        onStart(sampleRate)
        ReplayEngine(sampleRate).use { engine ->
            engine.setBphOverride(config.bphOverride)
            engine.setGateTrimDb(config.gateTrimDb)
            engine.setAnalysisMode(config.analysisMode)
            engine.setLiftAngleDeg(config.liftAngleDeg)

            val chunk = FloatArray(ReplayEngine.CHUNK_FRAMES)
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(chunk.size, samples.size - offset)
                samples.copyInto(chunk, 0, offset, offset + count)
                engine.process(chunk, count).forEach(onEvent)
                offset += count
            }
        }
    }
}
