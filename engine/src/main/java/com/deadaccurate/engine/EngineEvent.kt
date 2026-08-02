package com.deadaccurate.engine

/**
 * Events emitted by the native engine, decoded from the flat float records
 * defined in `core/include/deadaccurate/Events.h`. The two files must stay
 * in sync.
 */
sealed interface EngineEvent {
    /**
     * Signal level of one analysis hop (~30 Hz while capturing), measured on
     * the post-filter envelope so it shares a scale with the gate threshold.
     */
    data class Level(
        val rmsDb: Float,
        val peakDb: Float,
        val gateThresholdDb: Float,
        val gateOpen: Boolean,
        val calibrating: Boolean,
    ) : EngineEvent

    /**
     * One detected tick. [deltaFrames] is the audio-clock distance to the
     * previous tick (0 for the first of a session); accumulate in a Double
     * for absolute time. [accepted] false marks an outlier excluded from
     * rate estimation (rendered dimmed on the trace).
     */
    data class Tick(
        val deltaFrames: Float,
        val peakDb: Float,
        val accepted: Boolean,
    ) : EngineEvent

    /**
     * Beat-rate identification and rate-deviation snapshot (~2 Hz plus on
     * every change). [activeBph] 0 means still searching with no override;
     * [beatErrorMs] is null until both beat series have enough ticks.
     */
    data class Rate(
        val activeBph: Int,
        val locked: Boolean,
        val overridden: Boolean,
        val rateValid: Boolean,
        val secPerDay: Float,
        val tickCount: Int,
        val beatErrorMs: Float?,
    ) : EngineEvent

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
    private const val TYPE_TICK = 3
    private const val TYPE_RATE = 4

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
                        gateThresholdDb = buffer[base + 3],
                        gateOpen = buffer[base + 4] != 0f,
                        calibrating = buffer[base + 5] != 0f,
                    ),
                )
                TYPE_TICK -> events.add(
                    EngineEvent.Tick(
                        deltaFrames = buffer[base + 1],
                        peakDb = buffer[base + 2],
                        accepted = buffer[base + 3] != 0f,
                    ),
                )
                TYPE_RATE -> events.add(
                    EngineEvent.Rate(
                        activeBph = buffer[base + 1].toInt(),
                        locked = buffer[base + 2] != 0f,
                        overridden = buffer[base + 3] != 0f,
                        rateValid = buffer[base + 4] != 0f,
                        secPerDay = buffer[base + 5],
                        tickCount = buffer[base + 6].toInt(),
                        beatErrorMs = buffer[base + 7].takeIf { it >= 0f },
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
