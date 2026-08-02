package com.deadaccurate.engine

/**
 * Events emitted by the native engine, decoded from the flat float records
 * defined in `core/include/deadaccurate/Events.h`. The two files must stay
 * in sync.
 */
sealed interface EngineEvent {
    /** Signal level of one analysis hop (~30 Hz while capturing). */
    data class Level(val rmsDb: Float, val peakDb: Float) : EngineEvent

    /** Engine/stream state snapshot; emitted on every state change. */
    data class Status(
        val state: EngineState,
        val sampleRate: Int,
        val unprocessed: Boolean,
        val exclusiveMode: Boolean,
        val deviceId: Int,
        val errorCode: Int,
    ) : EngineEvent
}

enum class EngineState {
    IDLE,
    RUNNING,
    DISCONNECTED,
    ERROR,
    ;

    companion object {
        fun fromNative(value: Int): EngineState = entries.getOrElse(value) { ERROR }
    }
}

/** AAUDIO_INPUT_PRESET_* values the Kotlin layer chooses between (FR-1). */
object InputPreset {
    const val VOICE_RECOGNITION = 6
    const val UNPROCESSED = 9
}

object EventDecoder {
    /** Floats per event record; mirrors kEventFloats in Events.h. */
    const val EVENT_FLOATS = 8

    private const val TYPE_LEVEL = 1
    private const val TYPE_STATUS = 2

    /** Decodes [count] records from [buffer] as filled by nativeDrainEvents. */
    fun decode(buffer: FloatArray, count: Int): List<EngineEvent> {
        val events = ArrayList<EngineEvent>(count)
        for (i in 0 until count) {
            val base = i * EVENT_FLOATS
            when (buffer[base].toInt()) {
                TYPE_LEVEL -> events.add(
                    EngineEvent.Level(
                        rmsDb = buffer[base + 1],
                        peakDb = buffer[base + 2],
                    ),
                )
                TYPE_STATUS -> events.add(
                    EngineEvent.Status(
                        state = EngineState.fromNative(buffer[base + 1].toInt()),
                        sampleRate = buffer[base + 2].toInt(),
                        unprocessed = buffer[base + 3] != 0f,
                        exclusiveMode = buffer[base + 4] != 0f,
                        deviceId = buffer[base + 5].toInt(),
                        errorCode = buffer[base + 6].toInt(),
                    ),
                )
                // Unknown types are skipped: a newer native lib may emit
                // event types this decoder predates.
            }
        }
        return events
    }
}
