package com.deadaccurate.app.watchlog

import kotlin.math.sqrt

/**
 * Guesses which known movement is on the mic from its beat rate and
 * acoustic signature (per-band fold scores). The beat rate is the hard
 * discriminator — a 21,600 movement is never guessed as a 28,800 — and
 * within a rate the band signature (where the calibre puts its tick
 * energy) picks the closest labeled movement the app has heard before.
 * Every measurement the user saves with a movement reference teaches it.
 */
object MovementGuesser {

    /** Cosine similarity below this is "never heard this before". */
    private const val MIN_CONFIDENCE = 0.90f

    data class Guess(
        val movementRef: String,
        val watchName: String,
        /** Cosine similarity of normalized signatures, [MIN_CONFIDENCE]..1. */
        val confidence: Float,
    )

    fun guess(bph: Int, bandScores: List<Float>, watches: List<WatchEntry>): Guess? {
        val query = normalize(bandScores) ?: return null
        return labeledSignatures(bph, watches)
            .mapNotNull { (ref, entries) ->
                // Normalize each saved signature before averaging so a loud
                // recording doesn't outvote quiet ones — shape is what counts.
                val normalized = entries.mapNotNull { normalize(it.second) }
                val reference = normalized
                    .takeIf { it.isNotEmpty() }
                    ?.let { normalize(averageOf(it)) }
                reference?.let { Guess(ref, entries.first().first, dot(query, it)) }
            }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }
    }

    /** movementRef -> (watchName, signature) for same-bph labeled saves. */
    private fun labeledSignatures(
        bph: Int,
        watches: List<WatchEntry>,
    ): Map<String, List<Pair<String, List<Float>>>> {
        val byRef = LinkedHashMap<String, MutableList<Pair<String, List<Float>>>>()
        for (watch in watches) {
            val ref = watch.movementRef ?: continue
            for (m in watch.measurements) {
                if (m.bph == bph && m.bandScores.isNotEmpty()) {
                    byRef.getOrPut(ref) { mutableListOf() }.add(watch.name to m.bandScores)
                }
            }
        }
        return byRef
    }

    private fun averageOf(signatures: List<List<Float>>): List<Float> {
        val size = signatures.minOf { it.size }
        return List(size) { c -> signatures.map { it[c] }.average().toFloat() }
    }

    private fun normalize(scores: List<Float>): List<Float>? {
        val norm = sqrt(scores.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0f) scores.map { it / norm } else null
    }

    private fun dot(a: List<Float>, b: List<Float>): Float {
        var sum = 0f
        for (i in 0 until minOf(a.size, b.size)) {
            sum += a[i] * b[i]
        }
        return sum
    }
}
