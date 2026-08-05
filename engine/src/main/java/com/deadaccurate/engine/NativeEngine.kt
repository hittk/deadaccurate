package com.deadaccurate.engine

/**
 * Raw JNI seam to the native audio engine; use [AudioEngine] instead of
 * calling this directly. Signatures mirror docs/02-architecture.md
 * section 3.3.
 */
internal object NativeEngine {
    init {
        System.loadLibrary("deadaccurate_engine")
    }

    external fun nativeGetVersion(): String

    external fun nativeCreate(): Long

    /**
     * Returns 0 on success or a negative AAudio result code.
     * [deviceId] 0 lets the system route; [inputPreset] is an [InputPreset].
     */
    external fun nativeStart(handle: Long, deviceId: Int, inputPreset: Int): Int

    external fun nativeStop(handle: Long)

    external fun nativeDestroy(handle: Long)

    external fun nativeSetGateTrimDb(handle: Long, trimDb: Float)
    external fun nativeSetLiftAngleDeg(handle: Long, degrees: Float)

    external fun nativeRecalibrateGate(handle: Long)

    /** Positive pins the beat rate; 0 returns to auto-detection. */
    external fun nativeSetBphOverride(handle: Long, bph: Int)

    /** [AnalysisModeNative] value. */
    external fun nativeSetAnalysisMode(handle: Long, mode: Int)

    /**
     * Fills [out] with flat event records ([EventDecoder.EVENT_FLOATS] floats
     * each) and returns the number of events written.
     */
    external fun nativeDrainEvents(handle: Long, out: FloatArray): Int
}
