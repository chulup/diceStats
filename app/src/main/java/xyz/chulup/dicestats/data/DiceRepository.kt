package xyz.chulup.dicestats.data

import xyz.chulup.dicestats.data.db.DieDao
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.RollDao
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.data.photo.PhotoStorage
import xyz.chulup.dicestats.recognition.BoundingBox
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** A confirmed die from the confirm screen, ready to persist. */
data class ConfirmedDie(
    val dieId: Long,
    val value: Int,
    val confidence: Float,
    val boundingBox: BoundingBox,
    val wasCorrected: Boolean,
)

@Singleton
class DiceRepository @Inject constructor(
    private val dieDao: DieDao,
    private val rollDao: RollDao,
    private val photoStorage: PhotoStorage,
) {
    val dice: Flow<List<DieEntity>> = dieDao.observeAll()
    val rolls: Flow<List<RollWithResults>> = rollDao.observeRollsWithResults()

    suspend fun registerDie(name: String): Long =
        dieDao.insert(DieEntity(name = name, createdAt = System.currentTimeMillis()))

    /** Persists a roll and its per-die results; returns the new roll id. */
    suspend fun saveRoll(photoPath: String, capturedAt: Long, dice: List<ConfirmedDie>): Long {
        val rollId = rollDao.insertRoll(RollEntity(photoPath = photoPath, capturedAt = capturedAt))
        rollDao.insertResults(
            dice.map { die ->
                DieResultEntity(
                    rollId = rollId,
                    dieId = die.dieId,
                    value = die.value,
                    confidence = die.confidence,
                    boundingBox = die.boundingBox.encode(),
                    wasCorrected = die.wasCorrected,
                )
            },
        )
        return rollId
    }

    /** Deletes rolls (cascading to their results) and their photo files. */
    suspend fun deleteRolls(rollIds: List<Long>) {
        if (rollIds.isEmpty()) return
        val paths = rollDao.photoPathsFor(rollIds)
        rollDao.deleteRolls(rollIds)
        photoStorage.deletePhotos(paths)
    }
}

private fun BoundingBox.encode(): String = "$left,$top,$right,$bottom"
