package com.deadaccurate.app.watchlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovementGuesserTest {

    private fun watch(
        name: String,
        ref: String?,
        bph: Int,
        vararg signatures: List<Float>,
    ) = WatchEntry(
        id = name,
        name = name,
        movementRef = ref,
        measurements = signatures.map {
            Measurement(
                timestampMs = 0,
                bph = bph,
                secPerDay = 0f,
                beatErrorMs = null,
                mode = "CORRELATION",
                bandScores = it,
            )
        },
    )

    // Signatures shaped like real captures: the dominant band differs.
    private val highBandCalibre = listOf(1.0f, 2.0f, 3.0f, 8.0f)  // ST2533-like
    private val midBandCalibre = listOf(1.5f, 3.0f, 8.0f, 2.0f)   // NH-like

    @Test
    fun guessesTheLabeledMovementWithTheClosestSignature() {
        val watches = listOf(
            watch("Seagull", "ST2533", 21600, highBandCalibre),
            watch("SKX", "NH35", 21600, midBandCalibre),
        )
        val guess = MovementGuesser.guess(21600, listOf(1.1f, 2.1f, 3.2f, 7.7f), watches)
        assertEquals("ST2533", guess?.movementRef)
        assertTrue((guess?.confidence ?: 0f) > 0.95f)
    }

    @Test
    fun beatRateIsAHardDiscriminator() {
        // Same signature shape, wrong bph: never guessed.
        val watches = listOf(watch("Seagull", "ST2533", 21600, highBandCalibre))
        assertNull(MovementGuesser.guess(28800, highBandCalibre, watches))
    }

    @Test
    fun aDifferentSignatureIsNotGuessed() {
        val watches = listOf(watch("Seagull", "ST2533", 21600, highBandCalibre))
        // Energy in a completely different band.
        assertNull(MovementGuesser.guess(21600, listOf(9.0f, 2.0f, 1.0f, 0.5f), watches))
    }

    @Test
    fun unlabeledWatchesTeachNothing() {
        val watches = listOf(watch("Mystery", null, 21600, highBandCalibre))
        assertNull(MovementGuesser.guess(21600, highBandCalibre, watches))
    }

    @Test
    fun multipleSavesOfOneMovementAverageTogether() {
        val watches = listOf(
            watch(
                "SKX", "NH35", 21600,
                listOf(1.4f, 3.2f, 7.6f, 2.2f),
                listOf(1.6f, 2.8f, 8.4f, 1.8f),
            ),
        )
        val guess = MovementGuesser.guess(21600, midBandCalibre, watches)
        assertEquals("NH35", guess?.movementRef)
    }

    @Test
    fun emptySignatureNeverGuesses() {
        val watches = listOf(watch("SKX", "NH35", 21600, midBandCalibre))
        assertNull(MovementGuesser.guess(21600, emptyList(), watches))
    }
}
