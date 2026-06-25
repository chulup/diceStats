package xyz.chulup.dicestats.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface DieDao {
    @Query("SELECT * FROM dice ORDER BY name")
    fun observeAll(): Flow<List<DieEntity>>

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
