package xyz.chulup.dicestats.recognition

/**
 * One die recognized in a photo: where it is and, when readable, its pip value.
 *
 * @param value pip count 1..6, or null when the pips could not be read confidently.
 * @param blurProbability chance the top face is too blurry to read ([BlurDetector]); null when not assessed.
 * @param blurry [blurProbability] at or above the blur model's threshold — the value may be wrong.
 * @param score detector confidence 0..1; null for detectors that don't score (classical).
 * @param valueScore probability of [value] from a separate value classifier (two-stage); else null.
 * @param votes for a 2-of-3 [Consensus] die: how many pipelines found it; else null.
 * @param valueVotes for a consensus die: how many pipelines read [value] (0 when they disagreed).
 */
data class DetectedDie(
    val value: Int?,
    val boundingBox: BoundingBox,
    val blurProbability: Float? = null,
    val blurry: Boolean = false,
    val score: Float? = null,
    val valueScore: Float? = null,
    val votes: Int? = null,
    val valueVotes: Int? = null,
)
