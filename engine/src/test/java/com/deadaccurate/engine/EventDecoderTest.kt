package com.deadaccurate.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDecoderTest {

    private fun record(vararg values: Float): FloatArray =
        FloatArray(EventDecoder.EVENT_FLOATS).also { values.copyInto(it) }

    @Test
    fun decodesLevelEvent() {
        val buffer = record(1f, -42.5f, -30.25f, -48f, 1f, 0f)
        val events = EventDecoder.decode(buffer, 1)

        assertEquals(
            listOf(
                EngineEvent.Level(
                    rmsDb = -42.5f,
                    peakDb = -30.25f,
                    gateThresholdDb = -48f,
                    gateOpen = true,
                    calibrating = false,
                ),
            ),
            events,
        )
    }

    @Test
    fun decodesTickEvent() {
        val buffer = record(3f, 6000f, -20f, 1f)
        val events = EventDecoder.decode(buffer, 1)

        assertEquals(
            listOf(EngineEvent.Tick(deltaFrames = 6000f, peakDb = -20f, accepted = true)),
            events,
        )
    }

    @Test
    fun decodesRejectedTickEvent() {
        val buffer = record(3f, 2500f, -30f, 0f)
        val events = EventDecoder.decode(buffer, 1)

        assertEquals(
            listOf(EngineEvent.Tick(deltaFrames = 2500f, peakDb = -30f, accepted = false)),
            events,
        )
    }

    @Test
    fun decodesRateEvent() {
        val buffer = record(4f, 28800f, 1f, 0f, 1f, 14.4f, 120f)
        val events = EventDecoder.decode(buffer, 1)

        assertEquals(
            listOf(
                EngineEvent.Rate(
                    activeBph = 28800,
                    locked = true,
                    overridden = false,
                    rateValid = true,
                    secPerDay = 14.4f,
                    tickCount = 120,
                ),
            ),
            events,
        )
    }

    @Test
    fun decodesStatusEvent() {
        val buffer = record(2f, 1f, 48000f, 1f, 0f, 22f, 0f)
        val events = EventDecoder.decode(buffer, 1)

        assertEquals(
            listOf(
                EngineEvent.Status(
                    state = EngineState.RUNNING,
                    sampleRate = 48000,
                    unprocessed = true,
                    exclusiveMode = false,
                    deviceId = 22,
                    errorCode = 0,
                ),
            ),
            events,
        )
    }

    @Test
    fun decodesMultipleRecordsInOrder() {
        val level = record(1f, -50f, -40f, -55f, 0f, 0f)
        val status = record(2f, 2f, 48000f, 1f, 1f, 22f, -899f)
        val buffer = level + status
        val events = EventDecoder.decode(buffer, 2)

        assertEquals(2, events.size)
        assertTrue(events[0] is EngineEvent.Level)
        val decodedStatus = events[1] as EngineEvent.Status
        assertEquals(EngineState.DISCONNECTED, decodedStatus.state)
        assertEquals(-899, decodedStatus.errorCode)
    }

    @Test
    fun skipsUnknownEventTypes() {
        val unknown = record(99f, 1f, 2f)
        val level = record(1f, -10f, -5f, -55f, 0f, 0f)
        val events = EventDecoder.decode(unknown + level, 2)

        assertEquals(
            listOf(
                EngineEvent.Level(
                    rmsDb = -10f,
                    peakDb = -5f,
                    gateThresholdDb = -55f,
                    gateOpen = false,
                    calibrating = false,
                ),
            ),
            events,
        )
    }

    @Test
    fun ignoresRecordsBeyondCount() {
        val level = record(1f, -10f, -5f, -55f, 0f, 0f)
        val stale = record(1f, -99f, -99f, -99f, 0f, 0f)
        val events = EventDecoder.decode(level + stale, 1)

        assertEquals(1, events.size)
    }

    @Test
    fun outOfRangeStateDecodesAsError() {
        assertEquals(EngineState.ERROR, EngineState.fromNative(42))
    }
}
