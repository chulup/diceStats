package xyz.chulup.dicestats.data

import android.graphics.Bitmap
import xyz.chulup.dicestats.data.db.DieDao
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.DieRollCount
import xyz.chulup.dicestats.data.db.GameDao
import xyz.chulup.dicestats.data.db.GameEntity
import xyz.chulup.dicestats.data.db.GameRollCount
import xyz.chulup.dicestats.data.db.RollDao
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults
import xyz.chulup.dicestats.data.photo.PhotoStorage
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieColorSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    private val gameDao: GameDao,
    private val photoStorage: PhotoStorage,
) {
    val dice: Flow<List<DieEntity>> = dieDao.observeAll()

    /** Rolls with their per-die results; values impossible for their die are dropped. */
    val rolls: Flow<List<RollWithResults>> =
        combine(rollDao.observeRollsWithResults(), dice) { rolls, dice -> sanitizeRolls(rolls, dice) }

    /** Number of recorded results per die, keyed by die id. */
    val rollCountsByDie: Flow<List<DieRollCount>> = dieDao.observeRollCounts()

    /** All games, newest first. */
    val games: Flow<List<GameEntity>> = gameDao.observeAll()

    /** The currently open game (at most one), or null when none is running. */
    val activeGame: Flow<GameEntity?> = gameDao.observeActive()

    /** A single game by id (for the per-game stats screen). */
    fun game(gameId: Long): Flow<GameEntity?> = gameDao.observeById(gameId)

    /** The rolls (with their per-die results) captured during a game, newest first;
     *  values impossible for their die are dropped. */
    fun rollsForGame(gameId: Long): Flow<List<RollWithResults>> =
        combine(rollDao.observeRollsWithResultsForGame(gameId), dice) { rolls, dice ->
            sanitizeRolls(rolls, dice)
        }

    /** Number of rolls captured during each game, keyed by game id. */
    val rollCountsByGame: Flow<List<GameRollCount>> = gameDao.observeRollCountsByGame()

    /** Opens a new game; subsequent rolls are tagged to it until it is finished. */
    suspend fun startGame(name: String): Long =
        gameDao.insert(GameEntity(name = name, startedAt = System.currentTimeMillis()))

    /** Closes a game; later rolls are no longer tagged to it. */
    suspend fun finishGame(id: Long) =
        gameDao.finish(id, System.currentTimeMillis())

    /** Re-assigns existing rolls to [gameId] (e.g. from the gallery selection). */
    suspend fun assignRollsToGame(rollIds: List<Long>, gameId: Long) {
        if (rollIds.isEmpty()) return
        rollDao.assignToGame(rollIds, gameId)
    }

    fun die(dieId: Long): Flow<DieEntity?> = dieDao.observeById(dieId)

    /** All recorded face values for a die (for statistics), with impossible values dropped. */
    fun valuesForDie(dieId: Long): Flow<List<Int>> =
        combine(dieDao.observeById(dieId), dieDao.observeValuesForDie(dieId)) { die, values ->
            val type = DieType.fromFaces(die?.faces ?: DieType.DEFAULT.faces)
            values.filter { type.isValidValue(it) }
        }

    suspend fun registerDie(name: String): Long =
        dieDao.insert(DieEntity(name = name, createdAt = System.currentTimeMillis()))

    /** Persists a roll and its per-die results; returns the new roll id. */
    suspend fun saveRoll(photoPath: String, capturedAt: Long, dice: List<ConfirmedDie>): Long {
        // Tag the roll to the open game, if any, so it counts toward that session.
        val gameId = gameDao.activeGameId()
        val rollId = rollDao.insertRoll(
            RollEntity(photoPath = photoPath, capturedAt = capturedAt, gameId = gameId),
        )
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

/**
 * Drops every result whose recorded value isn't a possible face of its die, so the rest of
 * the app never sees impossible data. A result whose die is unknown — deleted (null
 * `dieId`) or simply absent from [dice] — is kept, since there's no die type to judge it
 * against. Rolls with no surviving results stay (empty) and are filtered later by the stats.
 */
internal fun sanitizeRolls(
    rolls: List<RollWithResults>,
    dice: List<DieEntity>,
): List<RollWithResults> {
    val typeByDie = dice.associate { it.id to DieType.fromFaces(it.faces) }
    return rolls.map { rwr ->
        val kept = rwr.results.filter { result ->
            val type = result.dieId?.let { typeByDie[it] } ?: return@filter true
            type.isValidValue(result.value)
        }
        if (kept.size == rwr.results.size) rwr else rwr.copy(results = kept)
    }
}
