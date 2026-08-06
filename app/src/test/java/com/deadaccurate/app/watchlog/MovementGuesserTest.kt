package com.deadaccurate.app.watchlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Calibrated against real recordings: the band-energy distributions below
 * are measured halves of actual captures (NH34 and ST2533 phone-mic, the
 * same ST2533 through a piezo, a 6R64 and an ETA 2824-2). Same movement
 * agrees at Bhattacharyya >= 0.993; different same-rate calibres sit near
 * 0.93-0.95; the same movement across different inputs is even further
 * apart — which is why matching is partitioned by input.
 */
class MovementGuesserTest {

    // (band fractions: 0.8-3k / 3-8k / 8-16k / 16-21.5k)
    private val nh34A = listOf(0.041f, 0.171f, 0.516f, 0.273f)
    private val nh34B = listOf(0.017f, 0.232f, 0.544f, 0.207f)
    private val st2533A = listOf(0.007f, 0.142f, 0.285f, 0.567f)
    private val st2533B = listOf(0.008f, 0.173f, 0.266f, 0.553f)
    private val st2533PiezoA = listOf(0.031f, 0.582f, 0.096f, 0.292f)
    private val r6r64A = listOf(0.047f, 0.030f, 0.491f, 0.432f)
    private val r6r64B = listOf(0.060f, 0.017f, 0.547f, 0.376f)
    private val etaA = listOf(0.032f, 0.074f, 0.364f, 0.530f)

    private fun watch(
        name: String,
        ref: String?,
        bph: Int,
        input: String = "built-in",
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
                bandEnergies = it,
                input = input,
            )
        },
    )

    private val phoneRefs = listOf(
        watch("Blizzard", "NH34", 21600, "built-in", nh34A),
        watch("Seagull", "ST2533", 21600, "built-in", st2533A),
    )

    @Test
    fun separatesSameRateCalibresByTimbre() {
        // The second half of each real recording must match its own
        // movement — and not the other 21,600 calibre.
        assertEquals(
            "NH34",
            MovementGuesser.guess(21600, "built-in", nh34B, phoneRefs)?.movementRef,
        )
        assertEquals(
            "ST2533",
            MovementGuesser.guess(21600, "built-in", st2533B, phoneRefs)?.movementRef,
        )
    }

    @Test
    fun neverMatchesAcrossInputs() {
        // The same physical ST2533 through the piezo: spectra shift so much
        // it must NOT be compared against phone-mic references at all.
        assertNull(MovementGuesser.guess(21600, "wired", st2533PiezoA, phoneRefs))
    }

    @Test
    fun closeCousinsAtTheSameRateStayUnguessed() {
        // 6R64 vs ETA 2824-2 (both 28,800) measured at BC 0.987 — real but
        // under the 0.99 bar: better silent than wrong.
        val refs = listOf(watch("SPB143", "6R64", 28800, "built-in", r6r64A))
        assertNull(MovementGuesser.guess(28800, "built-in", etaA, refs))
        // The 6R64's own second half still matches (BC 0.997).
        assertEquals(
            "6R64",
            MovementGuesser.guess(28800, "built-in", r6r64B, refs)?.movementRef,
        )
    }

    @Test
    fun beatRateIsAHardDiscriminator() {
        assertNull(MovementGuesser.guess(28800, "built-in", st2533B, phoneRefs))
    }

    @Test
    fun ambiguousRunnerUpSuppressesTheGuess() {
        // Two labels taught the *same* distribution: neither can win.
        val refs = listOf(
            watch("A", "NH35", 21600, "built-in", nh34A),
            watch("B", "NH34", 21600, "built-in", nh34A),
        )
        assertNull(MovementGuesser.guess(21600, "built-in", nh34B, refs))
    }

    @Test
    fun unlabeledOrLegacyEntriesTeachNothing() {
        val unlabeled = listOf(watch("Mystery", null, 21600, "built-in", nh34A))
        assertNull(MovementGuesser.guess(21600, "built-in", nh34B, unlabeled))
        // Legacy (pre-0.4.5) entries load with empty energies.
        val legacy = listOf(watch("Old", "NH34", 21600, "built-in"))
        assertNull(MovementGuesser.guess(21600, "built-in", nh34B, legacy))
    }
}
