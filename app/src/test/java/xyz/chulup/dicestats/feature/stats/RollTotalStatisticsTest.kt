package xyz.chulup.dicestats.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chulup.dicestats.data.DieType

class RollTotalStatisticsTest {

    @Test
    fun empty_hasNoGroups() {
        assertEquals(emptyList<RollTotalGroup>(), RollTotalStatistics.from(emptyList()))
    }

    @Test
    fun emptyRolls_areIgnored() {
        assertEquals(emptyList<RollTotalGroup>(), RollTotalStatistics.from(listOf(emptyList(), emptyList())))
    }

    @Test
    fun groupsByDiceCount_inAscendingOrder() {
        val groups = RollTotalStatistics.from(
            listOf(
                listOf(3, 4, 5), // 3 dice
                listOf(2),       // 1 die
                listOf(1, 6),    // 2 dice
            ),
        )
        assertEquals(listOf(1, 2, 3), groups.map { it.diceCount })
    }

    @Test
    fun twoDice_totalsAndRange() {
        // Totals: 2+3=5, 6+6=12, 1+1=2.
        val group = RollTotalStatistics.from(
            listOf(listOf(2, 3), listOf(6, 6), listOf(1, 1)),
        ).single()

        assertEquals(2, group.diceCount)
        assertEquals(3, group.rollCount)
        assertEquals(2, group.minTotal)
        assertEquals(12, group.maxTotal)
        assertEquals(11, group.counts.size) // totals 2..12

        // counts indexed by total - 2: total 2 -> idx 0, total 5 -> idx 3, total 12 -> idx 10.
        assertEquals(1, group.counts[0])
        assertEquals(1, group.counts[3])
        assertEquals(1, group.counts[10])
        assertEquals(3, group.counts.sum())
    }

    @Test
    fun meanAndExpected() {
        // Two two-dice rolls totalling 4 and 8 -> mean 6; expected = 3.5 * 2 = 7.
        val group = RollTotalStatistics.from(listOf(listOf(1, 3), listOf(2, 6))).single()
        assertEquals(6.0, group.mean, 1e-9)
        assertEquals(7.0, group.expectedMean, 1e-9)
    }

    @Test
    fun defaultsToD6() {
        assertEquals(DieType.D6, RollTotalStatistics.from(listOf(listOf(1, 2))).single().dieType)
    }

    // --- Other die types, values inside the possible range -------------------------------

    @Test
    fun d20_singleDie_spansOneToTwenty() {
        val group = RollTotalStatistics.from(
            listOf(listOf(1), listOf(20), listOf(10)),
            DieType.D20,
        ).single()

        assertEquals(1, group.diceCount)
        assertEquals(1, group.minTotal)
        assertEquals(20, group.maxTotal)
        assertEquals(1, group.step)
        assertEquals(20, group.counts.size) // totals 1..20
        assertEquals((1..20).toList(), group.totals)
        assertEquals(1, group.counts[0])   // total 1
        assertEquals(1, group.counts[9])   // total 10
        assertEquals(1, group.counts[19])  // total 20
        assertEquals(10.5, group.expectedMean, 1e-9)
    }

    @Test
    fun d100_singleDie_stepsByTen() {
        val group = RollTotalStatistics.from(
            listOf(listOf(0), listOf(90), listOf(50)),
            DieType.D100,
        ).single()

        assertEquals(0, group.minTotal)
        assertEquals(90, group.maxTotal)
        assertEquals(10, group.step)
        assertEquals(10, group.counts.size) // faces 0,10,…,90
        assertEquals(listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90), group.totals)
        assertEquals(1, group.counts[0]) // total 0  -> bucket 0
        assertEquals(1, group.counts[5]) // total 50 -> bucket 5
        assertEquals(1, group.counts[9]) // total 90 -> bucket 9
        assertEquals(45.0, group.expectedMean, 1e-9)
    }

    @Test
    fun d100_twoDice_totalsStepByTenAcrossFullRange() {
        val group = RollTotalStatistics.from(
            listOf(listOf(0, 90), listOf(50, 50)), // totals 90 and 100
            DieType.D100,
        ).single()

        assertEquals(2, group.diceCount)
        assertEquals(0, group.minTotal)
        assertEquals(180, group.maxTotal)
        assertEquals(19, group.counts.size) // 0,10,…,180
        assertEquals(1, group.counts[9])    // total 90  -> (90-0)/10
        assertEquals(1, group.counts[10])   // total 100 -> (100-0)/10
        assertEquals(90.0, group.expectedMean, 1e-9)
    }

    // --- Values outside the possible range (recognition slips) ---------------------------

    @Test
    fun allInvalidValues_areDropped_leavingNothingToTotal() {
        // Every value is impossible for a d6, so each roll empties out and no group remains.
        assertTrue(RollTotalStatistics.from(listOf(listOf(9, 9)), DieType.D6).isEmpty())
        assertTrue(RollTotalStatistics.from(listOf(listOf(0, -1)), DieType.D6).isEmpty())
        assertTrue(RollTotalStatistics.from(listOf(listOf(100, 100)), DieType.D6).isEmpty())
    }

    @Test
    fun d100_nonFaceValue_isDropped() {
        // 5 isn't a face of a d100 (faces step by 10).
        assertTrue(RollTotalStatistics.from(listOf(listOf(5)), DieType.D100).isEmpty())
    }

    @Test
    fun partlyInvalidRoll_keepsTheValidDice_andRegroupsByCount() {
        // A three-"d6" roll with one impossible 9 becomes a valid two-dice total of 7.
        val group = RollTotalStatistics.from(listOf(listOf(3, 4, 9)), DieType.D6).single()

        assertEquals(2, group.diceCount)     // the 9 is gone, so it's a two-dice roll
        assertEquals(1, group.rollCount)
        assertEquals(7.0, group.mean, 1e-9)  // 3 + 4, the dropped 9 never reaches the total
        assertEquals(1, group.counts[5])     // total 7 -> bucket 5
        assertEquals(1, group.counts.sum())
    }

    @Test
    fun invalidRoll_doesNotInflateTheValidGroup() {
        // [3,4] is a clean two-dice roll; [9,9] is all-invalid and drops out entirely.
        val group = RollTotalStatistics.from(
            listOf(listOf(3, 4), listOf(9, 9)),
            DieType.D6,
        ).single()

        assertEquals(2, group.diceCount)
        assertEquals(1, group.rollCount)     // the all-invalid roll is gone
        assertEquals(1, group.counts[5])     // total 7
        assertEquals(1, group.counts.sum())
    }
}
