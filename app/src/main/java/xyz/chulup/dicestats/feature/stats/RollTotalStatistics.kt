package xyz.chulup.dicestats.feature.stats

/**
 * Distribution of roll totals (the sum of all dice in a roll), grouped by how many
 * dice the roll had. Two-dice rolls sum to 2..12, three-dice to 3..18, and so on, so
 * each dice-count is its own distribution.
 *
 * Pure Kotlin so it stays JVM-unit-testable.
 *
 * @param counts indexed by `total - diceCount` (index 0 = the lowest possible total,
 *   [minTotal]; the last index = [maxTotal]).
 */
data class RollTotalGroup(
    val diceCount: Int,
    val rollCount: Int,
    val counts: List<Int>,
    val mean: Double,
) {
    /** Lowest possible total: every die shows 1. */
    val minTotal: Int get() = diceCount

    /** Highest possible total: every die shows 6. */
    val maxTotal: Int get() = diceCount * FACES

    /** Expected mean total for fair dice. */
    val expectedMean: Double get() = EXPECTED_FACE_MEAN * diceCount

    /** Largest bar count, for scaling a chart (at least 1 to avoid divide-by-zero). */
    val maxCount: Int get() = counts.maxOrNull()?.coerceAtLeast(1) ?: 1

    private companion object {
        const val FACES = 6
        const val EXPECTED_FACE_MEAN = 3.5
    }
}

object RollTotalStatistics {
    private const val FACES = 6

    /**
     * Builds one [RollTotalGroup] per distinct dice-count, in ascending dice-count
     * order. Each element of [rolls] is the face values of a single roll; empty rolls
     * are ignored.
     */
    fun from(rolls: List<List<Int>>): List<RollTotalGroup> =
        rolls.filter { it.isNotEmpty() }
            .groupBy { it.size }
            .toSortedMap()
            .map { (diceCount, group) ->
                val span = (FACES - 1) * diceCount + 1
                val counts = IntArray(span)
                var sum = 0L
                for (roll in group) {
                    val total = roll.sum()
                    sum += total
                    val index = (total - diceCount).coerceIn(0, span - 1)
                    counts[index]++
                }
                RollTotalGroup(
                    diceCount = diceCount,
                    rollCount = group.size,
                    counts = counts.toList(),
                    mean = sum.toDouble() / group.size,
                )
            }
}
