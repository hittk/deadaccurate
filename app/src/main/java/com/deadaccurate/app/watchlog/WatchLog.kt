package com.deadaccurate.app.watchlog

/**
 * One saved measurement of a watch. [bandScores] is the acoustic signature
 * (fold score of [bph] per analysis band) captured when the result was
 * saved; it is what movement recognition learns from.
 */
data class Measurement(
    val timestampMs: Long,
    val bph: Int,
    val secPerDay: Float,
    val beatErrorMs: Float?,
    val mode: String,
    val bandScores: List<Float>,
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
