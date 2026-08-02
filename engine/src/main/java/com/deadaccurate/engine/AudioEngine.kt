package com.deadaccurate.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Lifecycle-safe facade over the native capture engine. One instance per
 * process is expected; [release] must be called when the owner is destroyed.
 *
 * Events are delivered by polling the native lock-free queue at UI cadence
 * while [events] is collected (docs/02-architecture.md section 3.3).
 */
class AudioEngine {
    private var handle: Long = 0L

    val version: String get() = NativeEngine.nativeGetVersion()

    /**
     * Opens and starts the capture stream. Returns 0 on success or a
     * negative AAudio result code.
     */
    fun start(deviceId: Int, inputPreset: Int): Int {
        if (handle == 0L) {
            handle = NativeEngine.nativeCreate()
        }
        return NativeEngine.nativeStart(handle, deviceId, inputPreset)
    }

    fun stop() {
        if (handle != 0L) {
            NativeEngine.nativeStop(handle)
        }
    }

    /** Gate trim around the calibrated threshold, in dB (FR-3). */
    fun setGateTrimDb(trimDb: Float) {
        ensureHandle()
        NativeEngine.nativeSetGateTrimDb(handle, trimDb)
    }

    /** Re-measures the ambient noise floor (FR-3). */
    fun recalibrateGate() {
        ensureHandle()
        NativeEngine.nativeRecalibrateGate(handle)
    }

    /** Pins the beat rate to [bph] (FR-4 override); 0 = auto-detect. */
    fun setBphOverride(bph: Int) {
        ensureHandle()
        NativeEngine.nativeSetBphOverride(handle, bph)
    }

    private fun ensureHandle() {
        if (handle == 0L) {
            handle = NativeEngine.nativeCreate()
        }
    }

    fun release() {
        if (handle != 0L) {
            NativeEngine.nativeStop(handle)
            NativeEngine.nativeDestroy(handle)
            handle = 0L
        }
    }

    /** Cold flow; poll only while collected. Safe to collect once at a time. */
    val events: Flow<EngineEvent> = flow {
        val buffer = FloatArray(EventDecoder.EVENT_FLOATS * DRAIN_CAPACITY)
        while (currentCoroutineContext().isActive) {
            if (handle != 0L) {
                val count = NativeEngine.nativeDrainEvents(handle, buffer)
                EventDecoder.decode(buffer, count).forEach { emit(it) }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.Default)

    private companion object {
        const val POLL_INTERVAL_MS = 33L
        const val DRAIN_CAPACITY = 64
    }
}
