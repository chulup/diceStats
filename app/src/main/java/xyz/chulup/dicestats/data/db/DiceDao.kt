package xyz.chulup.dicestats.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A registered die paired with how many recorded results reference it. */
data class DieRollCount(val dieId: Long, val count: Int)

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

    @Query("SELECT * FROM dice WHERE id = :dieId")
    suspend fun getById(dieId: Long): DieEntity?

    @Query("UPDATE dice SET colorSignature = :signature, colorSamples = :samples WHERE id = :dieId")
    suspend fun updateColorSignature(dieId: Long, signature: String?, samples: Int)

    @Insert
    suspend fun insert(die: DieEntity): Long
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

    @Query("SELECT photoPath FROM rolls WHERE id IN (:ids)")
    suspend fun photoPathsFor(ids: List<Long>): List<String>

    @Query("DELETE FROM rolls WHERE id IN (:ids)")
    suspend fun deleteRolls(ids: List<Long>)
}
