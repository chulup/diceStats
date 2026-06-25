package xyz.chulup.dicestats.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One photo capture. Forward-looking FKs (game/dieGroup/player) are nullable in the
 * MVP so later versions need no destructive migration (DESIGN.md).
 */
@Entity(tableName = "rolls")
data class RollEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoPath: String,
    val capturedAt: Long,
    val notes: String? = null,
    val gameId: Long? = null,
    val dieGroupId: Long? = null,
    val playerId: Long? = null,
)
