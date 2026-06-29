package xyz.chulup.dicestats.feature.games

import xyz.chulup.dicestats.data.db.GameEntity
import xyz.chulup.dicestats.data.db.GameRollCount

/** One game as shown on the Games screen. */
data class GameRow(
    val id: Long,
    val name: String,
    val startedAt: Long,
    val endedAt: Long?,
    val rollCount: Int,
    val isActive: Boolean,
)

data class GamesUiState(
    val games: List<GameRow> = emptyList(),
) {
    val hasActiveGame: Boolean get() = games.any { it.isActive }
}

/**
 * Pure mapping from persisted games + per-game roll counts to display rows. The open game
 * (if any) is floated to the top; the rest stay in the newest-first order they arrive in.
 */
fun buildGamesUiState(
    games: List<GameEntity>,
    rollCounts: List<GameRollCount>,
): GamesUiState {
    val countById = rollCounts.associate { it.gameId to it.count }
    val rows = games.map { game ->
        GameRow(
            id = game.id,
            name = game.name,
            startedAt = game.startedAt,
            endedAt = game.endedAt,
            rollCount = countById[game.id] ?: 0,
            isActive = game.isActive,
        )
    }.sortedByDescending { it.isActive }
    return GamesUiState(games = rows)
}
