package xyz.chulup.dicestats.feature.dicemanage

import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.recognition.BoundingBox

/** A photo region showing a die (or a pool's dice): [box] is normalized to the photo. */
data class DiePicture(val photoPath: String, val box: BoundingBox)

/**
 * Picks the best taken photo for a die: the roll showing the most of its dice (up to
 * [dieCount], so a pool is pictured as a group), then the highest mean confidence,
 * then the newest. Rolls without a photo or without boxes for the die are skipped.
 * The returned box is the padded union of the die's boxes in that roll.
 */
fun pickBestPicture(dieId: Long, dieCount: Int, rolls: List<RollWithResults>): DiePicture? {
    data class Candidate(val path: String, val box: BoundingBox, val shown: Int, val conf: Float, val at: Long)

    val best = rolls.mapNotNull { rwr ->
        val path = rwr.roll.photoPath ?: return@mapNotNull null
        val boxes = rwr.results.filter { it.dieId == dieId }
            .mapNotNull { r -> parseBox(r.boundingBox)?.let { it to r.confidence } }
        if (boxes.isEmpty()) return@mapNotNull null
        Candidate(
            path = path,
            box = boxes.map { it.first }.union().padded(),
            shown = minOf(boxes.size, dieCount.coerceAtLeast(1)),
            conf = boxes.map { it.second }.average().toFloat(),
            at = rwr.roll.capturedAt,
        )
    }.maxWithOrNull(compareBy<Candidate>({ it.shown }, { it.conf }, { it.at })) ?: return null
    return DiePicture(best.path, best.box)
}

internal fun parseBox(encoded: String): BoundingBox? {
    val p = encoded.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (p.size != 4) return null
    val box = BoundingBox(p[0], p[1], p[2], p[3])
    return if (box.width > 0f && box.height > 0f) box else null
}

private fun List<BoundingBox>.union() = BoundingBox(
    minOf { it.left }, minOf { it.top }, maxOf { it.right }, maxOf { it.bottom },
)

private fun BoundingBox.padded(fraction: Float = 0.15f): BoundingBox {
    val dx = width * fraction
    val dy = height * fraction
    return BoundingBox(
        (left - dx).coerceAtLeast(0f), (top - dy).coerceAtLeast(0f),
        (right + dx).coerceAtMost(1f), (bottom + dy).coerceAtMost(1f),
    )
}
