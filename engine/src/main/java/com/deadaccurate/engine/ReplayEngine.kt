package com.deadaccurate.engine

/**
 * Offline analysis of recorded audio through the exact same DSP chain as
 * live capture. Feed chunks with [process]; events come back synchronously
 * in the same [EngineEvent] model. Not thread-safe; call from one thread
 * and [close] when done.
 */
class ReplayEngine(sampleRate: Int) : AutoCloseable {

    private var handle: Long = nativeCreate(sampleRate)

    private val eventBuffer = FloatArray(EventDecoder.EVENT_FLOATS * MAX_EVENTS_PER_CHUNK)

    fun setBphOverride(bph: Int) {
        checkOpen()
        nativeSetBphOverride(handle, bph)
    }

    fun setGateTrimDb(trimDb: Float) {
        checkOpen()
        nativeSetGateTrimDb(handle, trimDb)
    }

    fun process(samples: FloatArray, count: Int = samples.size): List<EngineEvent> {
        checkOpen()
        require(count <= CHUNK_FRAMES) { "chunk too large: $count" }
        val eventCount = nativeProcess(handle, samples, count, eventBuffer)
        return EventDecoder.decode(eventBuffer, eventCount)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun checkOpen() = check(handle != 0L) { "ReplayEngine is closed" }

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeSetBphOverride(handle: Long, bph: Int)
    private external fun nativeSetGateTrimDb(handle: Long, trimDb: Float)
    private external fun nativeProcess(
        handle: Long,
        samples: FloatArray,
        count: Int,
        outEvents: FloatArray,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("deadaccurate_engine")
        }

        /** Feed at most one second of 48 kHz audio per process call. */
        const val CHUNK_FRAMES = 48_000

        // Worst case per 1 s chunk: ~30 level frames + ~17 ticks + a rate
        // frame per tick on lock churn; 512 leaves a wide margin.
        private const val MAX_EVENTS_PER_CHUNK = 512
    }
}
