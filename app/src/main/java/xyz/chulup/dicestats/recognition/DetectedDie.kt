package xyz.chulup.dicestats.recognition

/**
 * One die recognized in a photo: where it is and, when readable, its pip value.
 *
 * @param value pip count 1..6, or null when the pips could not be read confidently.
 */
data class DetectedDie(
    val value: Int?,
    val boundingBox: BoundingBox,
)
