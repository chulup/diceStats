package xyz.chulup.dicestats.feature.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults

class GameStatsUiStateTest {

    private fun die(id: Long, name: String) =
        DieEntity(id = id, name = name, createdAt = 0)

    private fun roll(id: Long, results: List<Pair<Long?, Int>>) = RollWithResults(
        roll = RollEntity(id = id, photoPath = "p$id", capturedAt = id),
        results = results.mapIndexed { i, (dieId, value) ->
            DieResultEntity(
                id = id * 100 + i,
                rollId = id,
                dieId = dieId,
                value = value,
                confidence = 1f,
                boundingBox = "0,0,1,1",
                wasCorrected = false,
            )
        },
    )

    @Test
    fun empty_isFlaggedEmpty() {
        val state = buildGameStatsUiState("Catan", emptyList(), emptyList())
        assertTrue(state.isEmpty)
        assertEquals("Catan", state.gameName)
        assertTrue(state.totals.isEmpty())
        assertTrue(state.dice.isEmpty())
    }

    @Test
    fun aggregatesRollTotalsByDiceCount() {
        val rolls = listOf(
            roll(1, listOf(1L to 3, 2L to 4)), // two-dice total 7
            roll(2, listOf(1L to 6)),          // one-die total 6
        )
        val state = buildGameStatsUiState("G", rolls, listOf(die(1, "A"), die(2, "B")))

        assertEquals(2, state.rollCount)
        // One group per distinct dice-count, ascending.
        assertEquals(listOf(1, 2), state.totals.map { it.diceCount })
    }

    @Test
    fun perDieBreakdown_scopedToGameRolls_orderedByDieId() {
        // Die 1 is named "Zed", die 2 "Alpha", so id-order and name-order disagree.
        val rolls = listOf(
            roll(1, listOf(1L to 2, 2L to 5)),
            roll(2, listOf(1L to 2, 2L to 6)),
        )
        val state = buildGameStatsUiState("G", rolls, listOf(die(2, "Alpha"), die(1, "Zed")))

        // Ordered by die id (1 before 2), not by name.
        assertEquals(listOf("Zed", "Alpha"), state.dice.map { it.name })
        val zed = state.dice.first { it.name == "Zed" }
        assertEquals(2, zed.stats.total)
        // Die 1 rolled 2 twice → count for face "2" (index 1) is 2.
        assertEquals(2, zed.stats.counts[1])
    }

    @Test
    fun deletedDie_countsTowardTotalsButNotPerDieBreakdown() {
        // A result whose die was deleted (null dieId) still has a value.
        val rolls = listOf(roll(1, listOf(1L to 3, null to 4)))
        val state = buildGameStatsUiState("G", rolls, listOf(die(1, "A")))

        // Total uses both values (3 + 4 = 7, two dice).
        assertEquals(listOf(2), state.totals.map { it.diceCount })
        // Only the surviving, named die appears per-die.
        assertEquals(listOf("A"), state.dice.map { it.name })
    }
}
