package com.deadaccurate.engine

/**
 * JNI seam to the native audio engine.
 *
 * M0 exposes only a version smoke call proving the library loads and the
 * seam works. The real surface (create/start/stop/drainEvents, see
 * docs/02-architecture.md section 3.3) lands in M1.
 */
object NativeEngine {
    init {
        System.loadLibrary("deadaccurate_engine")
    }

    fun version(): String = nativeGetVersion()

    private external fun nativeGetVersion(): String
}
