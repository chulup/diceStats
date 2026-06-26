package xyz.chulup.dicestats.recognition

/**
 * Guesses which registered physical die a detected face belongs to by comparing
 * colour fingerprints (DESIGN.md identity matching). Pure Kotlin / JVM-testable.
 *
 * Identity is always a *suggestion*: the confirm screen pre-fills a confident guess
 * but the user can override it. Per DESIGN.md, the caller should still require a
 * manual pick when [Match.confidence] is below [IDENTITY_CONFIRM_THRESHOLD].
 */
class DieIdentifier {

    /** A registered die with a learned colour fingerprint. */
    data class Candidate(val dieId: Long, val signature: DieColorSignature)

    data class Match(val dieId: Long, val confidence: Float)

    /**
     * Best colour match for [query] among [candidates], or null when nothing is
     * close enough. Confidence falls off with distance and is damped when a second
     * candidate is almost as close (an ambiguous match the user should confirm).
     */
    fun identify(query: DieColorSignature, candidates: List<Candidate>): Match? {
        if (candidates.isEmpty()) return null

        val ranked = candidates
            .map { it to DieColorSignature.distance(query, it.signature) }
            .sortedBy { it.second }

        val (best, bestDist) = ranked.first()
        if (bestDist > DieColorSignature.MATCH_MAX_DISTANCE) return null

        // Closeness in [0,1]: 1 at an exact match, 0 at the rejection distance.
        val closeness = 1f - bestDist / DieColorSignature.MATCH_MAX_DISTANCE

        // Separation in [0,1]: 1 when the runner-up is far, 0 when it ties the best.
        val separation = ranked.getOrNull(1)?.let { (_, secondDist) ->
            ((secondDist - bestDist) / DieColorSignature.MATCH_MAX_DISTANCE).coerceIn(0f, 1f)
        } ?: 1f

        val confidence = (closeness * separation).coerceIn(0f, 1f)
        return Match(best.dieId, confidence)
    }

    companion object {
        /**
         * Below this confidence the user must pick the die manually. A starting
         * hypothesis to calibrate against real labelled rolls (DESIGN.md), not fixed.
         */
        const val IDENTITY_CONFIRM_THRESHOLD = 0.45f
    }
}
