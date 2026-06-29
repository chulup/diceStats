package xyz.chulup.dicestats.feature.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chulup.dicestats.data.db.GameEntity
import xyz.chulup.dicestats.data.db.GameRollCount

class GamesUiStateTest {

    @Test
    fun empty_hasNoGamesAndNoActive() {
        val state = buildGamesUiState(emptyList(), emptyList())
        assertEquals(emptyList<GameRow>(), state.games)
        assertFalse(state.hasActiveGame)
    }

    @Test
    fun joinsRollCounts_defaultingToZero() {
        val games = listOf(
            GameEntity(id = 1, name = "A", startedAt = 100, endedAt = 200),
            GameEntity(id = 2, name = "B", startedAt = 300, endedAt = 400),
        )
        val counts = listOf(GameRollCount(gameId = 1, count = 5))

        val rows = buildGamesUiState(games, counts).games.associateBy { it.id }

        assertEquals(5, rows.getValue(1).rollCount)
        assertEquals(0, rows.getValue(2).rollCount)
    }

    @Test
    fun activeGame_isFloatedToTop_andFlagged() {
        // Newest-first input with the open (endedAt == null) game in the middle.
        val games = listOf(
            GameEntity(id = 3, name = "Newest closed", startedAt = 300, endedAt = 350),
            GameEntity(id = 2, name = "Open", startedAt = 200, endedAt = null),
            GameEntity(id = 1, name = "Oldest closed", startedAt = 100, endedAt = 150),
        )

        val state = buildGamesUiState(games, emptyList())

        assertTrue(state.hasActiveGame)
        assertEquals(2, state.games.first().id)
        assertTrue(state.games.first().isActive)
        // Remaining closed games keep their newest-first order.
        assertEquals(listOf(3L, 1L), state.games.drop(1).map { it.id })
    }
}
