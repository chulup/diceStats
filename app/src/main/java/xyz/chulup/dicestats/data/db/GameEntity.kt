package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A timed play session (DESIGN.md v2). While a game is "open" (`endedAt == null`) every
 * new [RollEntity] is tagged with its [id]; at most one game is open at a time. The user
 * explicitly starts and finishes a game.
 *
 * @param usesDicePools gates confirm-screen auto-assignment: only while the active game
 *   uses pools are detections colour-matched to a pool assigned automatically
 *   (DESIGN.md "Die Pools").
 */
@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val usesDicePools: Boolean = false,
) {
    val isActive: Boolean get() = endedAt == null
}
