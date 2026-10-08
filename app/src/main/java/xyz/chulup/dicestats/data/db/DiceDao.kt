package xyz.chulup.dicestats.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A registered die paired with how many recorded results reference it. */
data class DieRollCount(val dieId: Long, val count: Int)

/** A game paired with how many rolls were captured during it. */
data class GameRollCount(val gameId: Long, val count: Int)

@Dao
interface DieDao {
    @Query("SELECT * FROM dice ORDER BY name")
    fun observeAll(): Flow<List<DieEntity>>

    @Query("SELECT * FROM dice WHERE id = :dieId")
    fun observeById(dieId: Long): Flow<DieEntity?>

    @Query("SELECT dieId, COUNT(*) AS count FROM die_results WHERE dieId IS NOT NULL GROUP BY dieId")
    fun observeRollCounts(): Flow<List<DieRollCount>>

    @Query("SELECT value FROM die_results WHERE dieId = :dieId")
    fun observeValuesForDie(dieId: Long): Flow<List<Int>>

    /** Distinct rolls a die appears in — diverges from the result count for pools. */
    @Query("SELECT COUNT(DISTINCT rollId) FROM die_results WHERE dieId = :dieId")
    fun observeRollCountForDie(dieId: Long): Flow<Int>

    @Query("SELECT * FROM dice WHERE id = :dieId")
    suspend fun getById(dieId: Long): DieEntity?

    @Query("UPDATE dice SET colorSignature = :signature, colorSamples = :samples WHERE id = :dieId")
    suspend fun updateColorSignature(dieId: Long, signature: String?, samples: Int)

    @Insert
    suspend fun insert(die: DieEntity): Long

    /** Results referencing the die keep their rows with a null `dieId` (FK SET_NULL). */
    @Query("DELETE FROM dice WHERE id = :dieId")
    suspend fun delete(dieId: Long)
}

@Dao
interface RollDao {
    @Insert
    suspend fun insertRoll(roll: RollEntity): Long

    @Insert
    suspend fun insertResults(results: List<DieResultEntity>)

    @Transaction
    @Query("SELECT * FROM rolls ORDER BY capturedAt DESC")
    fun observeRollsWithResults(): Flow<List<RollWithResults>>

    @Transaction
    @Query("SELECT * FROM rolls WHERE gameId = :gameId ORDER BY capturedAt DESC")
    fun observeRollsWithResultsForGame(gameId: Long): Flow<List<RollWithResults>>

    @Query("SELECT photoPath FROM rolls WHERE id IN (:ids) AND photoPath IS NOT NULL")
    suspend fun photoPathsFor(ids: List<Long>): List<String>

    @Query("DELETE FROM rolls WHERE id IN (:ids)")
    suspend fun deleteRolls(ids: List<Long>)

    @Query("UPDATE rolls SET gameId = :gameId WHERE id IN (:ids)")
    suspend fun assignToGame(ids: List<Long>, gameId: Long?)
}

@Dao
interface GameDao {
    @Insert
    suspend fun insert(game: GameEntity): Long

    @Query("SELECT * FROM games ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observeById(id: Long): Flow<GameEntity?>

    /** The single open game (most recent if data is ever inconsistent), or null. */
    @Query("SELECT * FROM games WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<GameEntity?>

    @Query("SELECT id FROM games WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun activeGameId(): Long?

    @Query("SELECT * FROM games WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): GameEntity?

    @Query("UPDATE games SET endedAt = :endedAt WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long)

    @Query("SELECT gameId AS gameId, COUNT(*) AS count FROM rolls WHERE gameId IS NOT NULL GROUP BY gameId")
    fun observeRollCountsByGame(): Flow<List<GameRollCount>>
}
