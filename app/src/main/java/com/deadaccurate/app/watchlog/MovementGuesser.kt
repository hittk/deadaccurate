package com.deadaccurate.app.watchlog

import kotlin.math.sqrt

/**
 * Guesses which known movement is on the mic. The beat rate is the hard
 * discriminator; within a rate, the *distribution* of tick energy across
 * the analysis bands is the timbre fingerprint, compared by Bhattacharyya
 * coefficient. Matching is strict on purpose: real recordings show the
 * same movement agreeing at >= 0.993 while different calibres at the same
 * rate sit near 0.93-0.95 — and it only compares signatures captured on
 * the SAME input, because a piezo and an air microphone hear the same
 * movement with completely different spectra (measured: the same ST2533
 * matched itself across inputs *worse* than it matched an NH34 within
 * one input).
 */
object MovementGuesser {

    /** Bhattacharyya coefficient below this is "never heard this before". */
    private const val MIN_MATCH = 0.99f

    /** Runner-up closer than this to the best makes the guess ambiguous. */
    private const val AMBIGUITY_MARGIN = 0.005f

    data class Guess(
        val movementRef: String,
        val watchName: String,
        /** Bhattacharyya coefficient of band-energy distributions, 0..1. */
        val confidence: Float,
    )

    fun guess(
        bph: Int,
        input: String,
        bandEnergies: List<Float>,
        watches: List<WatchEntry>,
    ): Guess? {
        val query = toDistribution(bandEnergies) ?: return null
        val ranked = references(bph, input, watches)
            .mapNotNull { (ref, entry) ->
                val (watchName, distributions) = entry
                toDistribution(average(distributions))
                    ?.let { Guess(ref, watchName, bhattacharyya(query, it)) }
            }
            .sortedByDescending { it.confidence }
        val best = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)
        val confident = best != null && best.confidence >= MIN_MATCH &&
            (runnerUp == null || best.confidence - runnerUp.confidence >= AMBIGUITY_MARGIN)
        return if (confident) best else null
    }

    /** movementRef -> (a watch name, saved distributions) for same bph+input. */
    private fun references(
        bph: Int,
        input: String,
        watches: List<WatchEntry>,
    ): Map<String, Pair<String, List<List<Float>>>> {
        val byRef = LinkedHashMap<String, Pair<String, MutableList<List<Float>>>>()
        for (watch in watches) {
            val ref = watch.movementRef ?: continue
            for (m in watch.measurements) {
                val usable = m.bph == bph && m.input == input && m.bandEnergies.isNotEmpty()
                if (usable) {
                    val distribution = toDistribution(m.bandEnergies) ?: continue
                    byRef.getOrPut(ref) { watch.name to mutableListOf() }
                        .second.add(distribution)
                }
            }
        }
        return byRef
    }

    private fun average(distributions: List<List<Float>>): List<Float> {
        val size = distributions.minOf { it.size }
        return List(size) { c -> distributions.map { it[c] }.average().toFloat() }
    }

    /** L1-normalize energies into a distribution; null if degenerate. */
    private fun toDistribution(energies: List<Float>): List<Float>? {
        val total = energies.sumOf { it.coerceAtLeast(0f).toDouble() }.toFloat()
        return if (total > 0f) energies.map { it.coerceAtLeast(0f) / total } else null
    }

    private fun bhattacharyya(p: List<Float>, q: List<Float>): Float {
        var sum = 0f
        for (i in 0 until minOf(p.size, q.size)) {
            sum += sqrt(p[i] * q[i])
        }
        return sum
    }
}
