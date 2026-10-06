package xyz.chulup.dicestats.data

/**
 * A supported kind of die, defined by its ordered face values. This is the single
 * source of truth for a die's value range, expected mean, and histogram bucketing, and
 * for deciding whether a recorded value is even possible — so external data (user input,
 * stored results) can be sanitized against it. Works for any of these dice, not just the
 * pip d6 the MVP shipped with.
 *
 * Face values:
 * - d4/d6/d8/d10/d12/d20 are numbered `1..N`.
 * - d100 is the percentile die: ten faces valued `0, 10, … 90`.
 *
 * [faces] is the conventional "N" in *dN* and is what [xyz.chulup.dicestats.data.db.DieEntity]
 * stores (so d100 → 100, even though it physically has ten [sides]); [fromFaces] maps it back.
 */
enum class DieType(val faces: Int, val faceValues: List<Int>) {
    D4(4, (1..4).toList()),
    D6(6, (1..6).toList()),
    D8(8, (1..8).toList()),
    D10(10, (1..10).toList()),
    D12(12, (1..12).toList()),
    D20(20, (1..20).toList()),
    D100(100, (0..9).map { it * 10 }),
    ;

    /** Number of distinct physical faces. */
    val sides: Int get() = faceValues.size

    val minValue: Int get() = faceValues.first()
    val maxValue: Int get() = faceValues.last()

    /** Uniform-distribution expected mean of a single roll of this die. */
    val expectedMean: Double get() = faceValues.average()

    /**
     * Constant spacing between adjacent face values (1 for the numbered dice, 10 for
     * d100). The face values are evenly spaced, so this is exact.
     */
    val step: Int get() = if (sides > 1) faceValues[1] - faceValues[0] else 1

    /**
     * The position of [value] among this die's faces (0-based, low to high), or null
     * when [value] isn't one of its faces — e.g. a 7 on a d6, or a 35 on a d100.
     */
    fun faceIndex(value: Int): Int? {
        if (value < minValue || value > maxValue) return null
        val offset = value - minValue
        return if (offset % step == 0) offset / step else null
    }

    /** True when [value] is one of this die's faces. */
    fun isValidValue(value: Int): Boolean = faceIndex(value) != null

    companion object {
        val DEFAULT = D6

        /**
         * The die type stored as `faces` on a [xyz.chulup.dicestats.data.db.DieEntity]
         * (the *dN* number). Unknown counts fall back to [DEFAULT] so old/odd data still
         * resolves rather than crashing.
         */
        fun fromFaces(faces: Int): DieType = entries.firstOrNull { it.faces == faces } ?: DEFAULT
    }
}
