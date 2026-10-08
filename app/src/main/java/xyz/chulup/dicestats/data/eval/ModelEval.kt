package xyz.chulup.dicestats.data.eval

import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DetectedDie

/** How a confirmed die got onto the confirm screen. */
enum class DieOrigin {
    /** Found by the full-photo recognition pass. */
    DETECTED,

    /** Found by the long-press re-detection around a tap. */
    ADDED,

    /** Nothing was detected at the long-press; the user placed and sized the box. */
    PLACEHOLDER,
}

/** One die as the user saved it — the ground truth the models are scored against. */
data class ConfirmedBox(
    val box: BoundingBox,
    val value: Int,
    val recognizedValue: Int?,
    val origin: DieOrigin,
    val blurry: Boolean,
    /** The user changed the value shown. */
    val edited: Boolean = false,
    /** The user dragged the box. */
    val moved: Boolean = false,
)

/**
 * How the confirm screen went for one photo — the cost to the user of the recognition shown.
 * [latencyMs] from opening the photo until the dice appeared (decode + recognition); [confirmMs]
 * from then until Save; counts of dice removed, added by long-press, value edits, region re-detects.
 */
data class ConfirmSession(
    val latencyMs: Long,
    val confirmMs: Long,
    val removed: Int,
    val added: Int,
    val valueEdits: Int,
    val redetects: Int,
)

/**
 * A model's result on one photo against the confirmed dice: [found] confirmed dice it boxed
 * (IoU ≥ [MATCH_IOU]), [valueCorrect] of those with the right value, [missed] confirmed dice
 * it didn't box, [extra] boxes that matched no confirmed die.
 */
data class RunScore(val found: Int, val valueCorrect: Int, val missed: Int, val extra: Int)

const val MATCH_IOU = 0.5f

/** Scores [predicted] against [confirmed]: greedy one-to-one matching, highest IoU first. */
fun scoreRun(predicted: List<DetectedDie>, confirmed: List<ConfirmedBox>): RunScore {
    val pairs = predicted.indices.flatMap { p ->
        confirmed.indices.map { c -> Triple(p, c, iou(predicted[p].boundingBox, confirmed[c].box)) }
    }.filter { it.third >= MATCH_IOU }.sortedByDescending { it.third }
    val usedP = HashSet<Int>()
    val usedC = HashSet<Int>()
    var correct = 0
    for ((p, c, _) in pairs) {
        if (p in usedP || c in usedC) continue
        usedP += p
        usedC += c
        if (predicted[p].value == confirmed[c].value) correct++
    }
    return RunScore(
        found = usedC.size,
        valueCorrect = correct,
        missed = confirmed.size - usedC.size,
        extra = predicted.size - usedP.size,
    )
}

private fun iou(a: BoundingBox, b: BoundingBox): Float {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val inter = (right - left) * (bottom - top)
    return inter / (a.width * a.height + b.width * b.height - inter)
}
