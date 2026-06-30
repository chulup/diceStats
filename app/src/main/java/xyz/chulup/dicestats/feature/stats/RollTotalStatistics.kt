package xyz.chulup.dicestats.feature.stats

import xyz.chulup.dicestats.data.DieType

/**
 * Distribution of roll totals (the sum of all dice in a roll), grouped by how many
 * dice the roll had. For d6, two-dice rolls sum to 2..12, three-dice to 3..18, and so
 * on, so each dice-count is its own distribution. The possible totals — and the spacing
 * between them — come from the [dieType]: d100 totals, for instance, step by 10.
 *
 * Pure Kotlin so it stays JVM-unit-testable.
 *
 * @param counts indexed by `(total − minTotal) / step` (index 0 = the lowest possible
 *   total, [minTotal]; the last index = [maxTotal]).
 */
data class RollTotalGroup(
    val diceCount: Int,
    val rollCount: Int,
    val counts: List<Int>,
    val mean: Double,
    /** The die type assumed for these rolls; drives the total range and spacing. */
    val dieType: DieType = DieType.DEFAULT,
) {
    /** Lowest possible total: every die shows its minimum face. */
    val minTotal: Int get() = diceCount * dieType.minValue

    /** Highest possible total: every die shows its maximum face. */
    val maxTotal: Int get() = diceCount * dieType.maxValue

    /** Spacing between adjacent possible totals (1 for numbered dice, 10 for d100). */
    val step: Int get() = dieType.step

    /** Expected mean total for fair dice. */
    val expectedMean: Double get() = dieType.expectedMean * diceCount

    /** The total each bucket in [counts] represents, low to high (spaced by [step]). */
    val totals: List<Int> get() = counts.indices.map { minTotal + it * step }

    /** Largest bar count, for scaling a chart (at least 1 to avoid divide-by-zero). */
    val maxCount: Int get() = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
}

object RollTotalStatistics {

    /**
     * Builds one [RollTotalGroup] per distinct dice-count, in ascending dice-count
     * order, treating every die as a [dieType] (d6 by default). Each element of [rolls]
     * is the face values of a single roll. Values that aren't a face of the die (a
     * recognition slip) are dropped before totalling; a roll left with no valid values
     * — like an empty one — is ignored. Because every counted total is therefore in
     * range, the bucket index is always valid.
     */
    fun from(rolls: List<List<Int>>, dieType: DieType = DieType.DEFAULT): List<RollTotalGroup> =
        rolls.map { roll -> roll.filter { dieType.isValidValue(it) } }
            .filter { it.isNotEmpty() }
            .groupBy { it.size }
            .toSortedMap()
            .map { (diceCount, group) ->
                val minTotal = diceCount * dieType.minValue
                val step = dieType.step
                val span = diceCount * (dieType.sides - 1) + 1
                val counts = IntArray(span)
                var sum = 0L
                for (roll in group) {
                    val total = roll.sum()
                    sum += total
                    counts[(total - minTotal) / step]++
                }
                RollTotalGroup(
                    diceCount = diceCount,
                    rollCount = group.size,
                    counts = counts.toList(),
                    mean = sum.toDouble() / group.size,
                    dieType = dieType,
                )
            }
}
