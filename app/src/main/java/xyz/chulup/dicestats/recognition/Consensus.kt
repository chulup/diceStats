package xyz.chulup.dicestats.recognition

/**
 * Majority vote over several pipelines' results on one photo ("2 of 3"): a die is kept when at
 * least [minVotes] pipelines boxed it (boxes grouped by IoU ≥ [MATCH_IOU]), and its value is the
 * one at least [minVotes] of them read — otherwise null, shown as an unread "?" for the user.
 * The box is the mean of the grouped boxes.
 */
object Consensus {

    const val MATCH_IOU = 0.5f
    const val MIN_VOTES = 2

    /** One agreed die: [votes] pipelines found it, [valueVotes] of them read [value]. */
    data class Die(val box: BoundingBox, val value: Int?, val votes: Int, val valueVotes: Int, val score: Float?)

    fun vote(results: List<List<DetectedDie>>, minVotes: Int = MIN_VOTES): List<Die> {
        // Each group holds at most one die per pipeline; dice join the best-overlapping group.
        val groups = mutableListOf<MutableMap<Int, DetectedDie>>()
        for ((p, dice) in results.withIndex()) {
            val candidates = dice.flatMap { die ->
                groups.indices.filter { p !in groups[it] }.map { g -> Triple(die, g, iou(die.boundingBox, mean(groups[g].values))) }
            }.filter { it.third >= MATCH_IOU }.sortedByDescending { it.third }
            val placed = HashSet<DetectedDie>()
            val taken = HashSet<Int>()
            for ((die, g, _) in candidates) {
                if (die in placed || g in taken) continue
                groups[g][p] = die
                placed += die
                taken += g
            }
            dice.filter { it !in placed }.forEach { groups += mutableMapOf(p to it) }
        }
        return groups.filter { it.size >= minVotes }.map { g ->
            val (value, count) = g.values.mapNotNull { it.value }.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.toPair() ?: (null to 0)
            val scores = g.values.mapNotNull { it.score }
            Die(
                box = mean(g.values),
                value = value.takeIf { count >= minVotes },
                votes = g.size,
                valueVotes = if (count >= minVotes) count else 0,
                score = scores.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            )
        }.sortedWith(compareBy({ it.box.top }, { it.box.left }))
    }

    private fun mean(dice: Collection<DetectedDie>) = BoundingBox(
        dice.map { it.boundingBox.left }.average().toFloat(),
        dice.map { it.boundingBox.top }.average().toFloat(),
        dice.map { it.boundingBox.right }.average().toFloat(),
        dice.map { it.boundingBox.bottom }.average().toFloat(),
    )

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width * a.height + b.width * b.height - inter)
    }
}
