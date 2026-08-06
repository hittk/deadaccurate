package com.deadaccurate.app.watchlog

/**
 * One saved measurement of a watch. [bandEnergies] is the acoustic
 * signature — the folded tick energy per analysis band at [bph] — and
 * [input] records which input heard it ("wired", "built-in", "replay").
 * Recognition compares energy *distributions* and only within the same
 * input, because a piezo and an air microphone hear the same movement
 * with completely different spectra.
 */
data class Measurement(
    val timestampMs: Long,
    val bph: Int,
    val secPerDay: Float,
    val beatErrorMs: Float?,
    val amplitudeDeg: Float? = null,
    val mode: String,
    val bandEnergies: List<Float> = emptyList(),
    val input: String = "",
)

/**
 * A specific physical watch the user tracks over time. [movementRef] is the
 * calibre inside it (e.g. "NH35"), labeled by the user at save time —
 * every labeled measurement teaches the recognizer what that movement
 * sounds like.
 */
data class WatchEntry(
    val id: String,
    val name: String,
    val movementRef: String?,
    val measurements: List<Measurement>,
)
