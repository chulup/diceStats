package xyz.chulup.dicestats.data

import android.graphics.Bitmap
import xyz.chulup.dicestats.data.db.DieDao
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.DieRollCount
import xyz.chulup.dicestats.data.db.RollDao
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.data.photo.PhotoStorage
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieColorSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A confirmed die from the confirm screen, ready to persist. */
data class ConfirmedDie(
    val dieId: Long,
    val value: Int,
    val confidence: Float,
    val boundingBox: BoundingBox,
    val wasCorrected: Boolean,
    /** Colour fingerprint of this crop, folded into the die's learned signature. */
    val colorSignature: DieColorSignature? = null,
)

@Singleton
class DiceRepository @Inject constructor(
    private val dieDao: DieDao,
    private val rollDao: RollDao,
    private val photoStorage: PhotoStorage,
) {
    val dice: Flow<List<DieEntity>> = dieDao.observeAll()
    val rolls: Flow<List<RollWithResults>> = rollDao.observeRollsWithResults()

    /** Number of recorded results per die, keyed by die id. */
    val rollCountsByDie: Flow<List<DieRollCount>> = dieDao.observeRollCounts()

    fun die(dieId: Long): Flow<DieEntity?> = dieDao.observeById(dieId)

    /** All recorded face values for a die (for statistics). */
    fun valuesForDie(dieId: Long): Flow<List<Int>> = dieDao.observeValuesForDie(dieId)

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
        // Learn/refine each die's colour fingerprint from this confirmed roll.
        dice.forEach { die -> die.colorSignature?.let { learnColor(die.dieId, it) } }
        return rollId
    }

    /** Folds a freshly observed crop colour into the die's running fingerprint. */
    private suspend fun learnColor(dieId: Long, observed: DieColorSignature) {
        val die = dieDao.getById(dieId) ?: return
        val existing = die.colorSignature?.let { DieColorSignature.decode(it) }
        val merged = if (existing == null) observed
            else DieColorSignature.merge(existing, die.colorSamples, observed)
        dieDao.updateColorSignature(dieId, merged.encode(), die.colorSamples + 1)
    }

    /** Persists [bitmap] as a new roll photo and returns its absolute path. */
    suspend fun storePhoto(bitmap: Bitmap): String =
        withContext(Dispatchers.IO) { photoStorage.writePhoto(bitmap).absolutePath }

    /** Files a badly-recognized capture (photo + metadata) for later analysis. */
    suspend fun reportUnrecognized(photoPath: String, metadataJson: String) {
        withContext(Dispatchers.IO) {
            photoStorage.saveReport(File(photoPath), metadataJson)
        }
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
