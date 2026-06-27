package xyz.chulup.dicestats.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
