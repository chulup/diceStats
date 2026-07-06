package xyz.chulup.dicestats.feature.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.ConfirmedDie
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieColorAnalyzer
import xyz.chulup.dicestats.recognition.DieColorSignature
import xyz.chulup.dicestats.recognition.DieIdentifier
import xyz.chulup.dicestats.recognition.DieRecognizer
import xyz.chulup.dicestats.recognition.mapOrientedRegionToRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** One detected die on the confirm screen: editable value + chosen registered die. */
data class DieAssignment(
    val boundingBox: BoundingBox,
    val value: Int,
    val recognizedValue: Int?,
    val dieId: Long?,
    /** Confidence of an auto-guessed identity (null when none / user-assigned). */
    val dieIdConfidence: Float? = null,
    /** Colour fingerprint of this crop, carried through to persist + learn. */
    val signature: DieColorSignature? = null,
    /** True once the user has set the value (used to resolve an unread "?" die). */
    val edited: Boolean = false,
) {
    /** Whether a definite value is established (auto-recognized or user-set). */
    val hasValue: Boolean get() = recognizedValue != null || edited
}

/** The type of the registered die this assignment points at, or null if unassigned/unknown. */
private fun DieAssignment.dieTypeIn(registered: List<DieEntity>): DieType? =
    dieId?.let { id -> registered.firstOrNull { it.id == id } }?.let { DieType.fromFaces(it.faces) }

/**
 * The die that follows [current] in [order], wrapping past the end — so tapping boxes
 * in turn walks down the palette. Dice already at their per-roll capacity (per
 * [assignedCounts]) are skipped; null when [order] is empty or every die is full.
 * Falls back to the first entry when [current] isn't in [order] (`indexOfFirst` -> -1,
 * so -1+1 == 0).
 */
internal fun nextActiveDie(
    order: List<DieEntity>,
    current: Long,
    assignedCounts: Map<Long, Int> = emptyMap(),
): Long? {
    if (order.isEmpty()) return null
    val cur = order.indexOfFirst { it.id == current }
    for (step in 1..order.size) {
        val candidate = order[(cur + step) % order.size]
        if ((assignedCounts[candidate.id] ?: 0) < candidate.count) return candidate.id
    }
    return null
}

/**
 * The palette die to highlight after a box was identified as [justAssigned]: a pool
 * with remaining per-roll capacity keeps the highlight (tap-tap-tap fills the pool),
 * otherwise the highlight advances to the next non-full die.
 */
internal fun activeDieAfterAssignment(
    order: List<DieEntity>,
    justAssigned: Long,
    assignedCounts: Map<Long, Int>,
): Long? {
    val die = order.firstOrNull { it.id == justAssigned }
    if (die != null && (assignedCounts[die.id] ?: 0) < die.count) return justAssigned
    return nextActiveDie(order, justAssigned, assignedCounts)
}

/**
 * Unassigns the lowest-confidence auto-guesses that would put a die over its per-roll
 * capacity, so recognition never proposes more boxes on a pool than it has dice. (The
 * user can still over-assign manually; [DetectionUiState.Ready.canSave] blocks that.)
 */
internal fun capAutoAssignments(
    assignments: List<DieAssignment>,
    registered: List<DieEntity>,
): List<DieAssignment> {
    val capacity = registered.associate { it.id to it.count }
    val drop = mutableSetOf<Int>()
    assignments.withIndex()
        .filter { it.value.dieId != null }
        .groupBy { it.value.dieId!! }
        .forEach { (dieId, entries) ->
            val cap = capacity[dieId] ?: return@forEach
            if (entries.size > cap) {
                entries.sortedByDescending { it.value.dieIdConfidence ?: 0f }
                    .drop(cap)
                    .forEach { drop += it.index }
            }
        }
    if (drop.isEmpty()) return assignments
    return assignments.mapIndexed { i, a ->
        if (i in drop) a.copy(dieId = null, dieIdConfidence = null) else a
    }
}

/**
 * Power-of-two `inSampleSize` that keeps the longest edge at or above [targetMaxEdge]
 * (BitmapFactory halves per step), so a decoded bitmap stays large enough to detect on
 * without wasting memory on full-resolution captures.
 */
internal fun sampleSizeFor(width: Int, height: Int, targetMaxEdge: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= targetMaxEdge) {
        longest /= 2
        sample *= 2
    }
    return sample
}

sealed interface DetectionUiState {
    data object Loading : DetectionUiState

    data class Ready(
        /** The photo currently shown — the capture, or a cropped region the user re-detected. */
        val photoPath: String,
        val aspectRatio: Float,
        val dice: List<DieAssignment>,
        val registeredDice: List<DieEntity>,
        /** Die that the next tapped box will be identified as ("next die from the list"). */
        val activeDieId: Long? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        /** True while re-detection on a cropped region is running. */
        val detecting: Boolean = false,
    ) : DetectionUiState {
        /**
         * Every die must be assigned and have a value that's a real face of it, and no
         * die may carry more boxes than its per-roll capacity — a pool of 3 can't
         * appear 4 times in one photo (DESIGN.md "Die Pools"; fewer is fine).
         */
        val canSave: Boolean
            get() = dice.isNotEmpty() &&
                dice.all { it.dieTypeIn(registeredDice)?.isValidValue(it.value) == true } &&
                assignedCounts.all { (id, n) ->
                    (registeredDice.firstOrNull { it.id == id }?.count ?: 0) >= n
                }

        /** Boxes assigned to each die in this roll, keyed by die id. */
        val assignedCounts: Map<Long, Int>
            get() = dice.mapNotNull { it.dieId }.groupingBy { it }.eachCount()

        /** Dice whose per-roll capacity is exhausted — the palette grays these out. */
        val atCapacityDieIds: Set<Long>
            get() {
                val counts = assignedCounts
                return registeredDice
                    .filter { (counts[it.id] ?: 0) >= it.count }
                    .map { it.id }
                    .toSet()
            }

        /** Registered dice ordered most-recent-first — the tap-to-identify palette. */
        val recentDice: List<DieEntity>
            get() = registeredDice.sortedByDescending { it.createdAt }
    }

    data class Error(val message: String) : DetectionUiState
}

/**
 * Recognizes dice in the captured photo (detection + pip counting), lets the user
 * confirm/correct each value and assign it to a registered die, then persists the
 * roll (DEVPLAN.md step 4).
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DiceRepository,
) : ViewModel() {

    /** The working photo — starts as the capture, becomes the crop when re-detecting. */
    private var currentPhotoPath: String =
        savedStateHandle.get<String>(ARG_PHOTO_PATH) ?: error("photoPath argument required")

    private val recognizer = DieRecognizer()
    private val colorAnalyzer = DieColorAnalyzer()
    private val identifier = DieIdentifier()

    private val registeredDice = repository.dice
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<DetectionUiState>(DetectionUiState.Loading)
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    /** Flips true once the photo has been filed for later analysis (drives navigation). */
    private val _reported = MutableStateFlow(false)
    val reported: StateFlow<Boolean> = _reported.asStateFlow()

    init {
        recognize()
        // Keep the die picker in sync as dice get registered, defaulting the active
        // (next-to-assign) die to the most recent one when none is chosen yet.
        viewModelScope.launch {
            registeredDice.collect { dice ->
                _uiState.update { state ->
                    if (state !is DetectionUiState.Ready) return@update state
                    val active = state.activeDieId ?: dice.maxByOrNull { it.createdAt }?.id
                    state.copy(registeredDice = dice, activeDieId = active)
                }
            }
        }
    }

    private fun recognize() {
        viewModelScope.launch {
            _uiState.value = DetectionUiState.Loading
            val result = runCatching {
                val bitmap = withContext(Dispatchers.Default) { decodeOriented(currentPhotoPath) }
                    ?: error("Could not decode photo")
                val assignments = buildAssignments(bitmap, registeredDice.value)
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                bitmap.recycle()
                ratio to assignments
            }
            _uiState.value = result.fold(
                onSuccess = { (ratio, dice) ->
                    val registered = registeredDice.value
                    // Start the palette on the newest die that still has room in this
                    // roll (auto-assignment may have filled a pool already).
                    val counts = dice.mapNotNull { it.dieId }.groupingBy { it }.eachCount()
                    val active = registered
                        .sortedByDescending { it.createdAt }
                        .firstOrNull { (counts[it.id] ?: 0) < it.count }
                    DetectionUiState.Ready(
                        photoPath = currentPhotoPath,
                        aspectRatio = ratio,
                        dice = dice,
                        registeredDice = registered,
                        activeDieId = active?.id,
                    )
                },
                onFailure = { DetectionUiState.Error(it.message ?: "Detection failed") },
            )
        }
    }

    /**
     * Re-detects on the user-selected [region] (normalized, in display coordinates):
     * crops the current photo to that region at native resolution, makes the crop the
     * new working photo, and runs detection afresh — replacing the prior photo and
     * results so the user can drill into dice the full-frame pass missed.
     */
    fun detectInRegion(region: BoundingBox) {
        val state = _uiState.value as? DetectionUiState.Ready ?: return
        if (state.detecting) return
        val previousPath = currentPhotoPath
        _uiState.update { (it as DetectionUiState.Ready).copy(detecting = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val crop = decodeRegion(previousPath, region) ?: return@withContext null
                val dice = buildAssignments(crop, registeredDice.value)
                val ratio = crop.width.toFloat() / crop.height.toFloat()
                val newPath = repository.storePhoto(crop)
                crop.recycle()
                Triple(newPath, ratio, dice)
            }
            if (outcome == null) {
                _uiState.update { if (it is DetectionUiState.Ready) it.copy(detecting = false) else it }
                return@launch
            }
            val (newPath, ratio, dice) = outcome
            currentPhotoPath = newPath
            withContext(Dispatchers.IO) { runCatching { File(previousPath).delete() } }
            _uiState.update { current ->
                if (current !is DetectionUiState.Ready) return@update current
                current.copy(
                    photoPath = newPath,
                    aspectRatio = ratio,
                    dice = dice,
                    detecting = false,
                )
            }
        }
    }

    /** Builds confirm-screen assignments from a freshly detected [bitmap]. */
    private suspend fun buildAssignments(
        bitmap: Bitmap,
        registered: List<DieEntity>,
    ): List<DieAssignment> {
        val detected = recognizer.recognize(bitmap)
        // Pools are auto-assign candidates only inside a game that opted into them
        // (DESIGN.md "Die Pools"); individual dice are matched as before.
        val poolsEnabled = repository.activeGameNow()?.usesDicePools == true
        val candidates = identityCandidates(registered.filter { poolsEnabled || !it.isPool })
        val assignments = detected.map { det ->
            val signature = colorAnalyzer.signature(bitmap, det.boundingBox)
            val guess = signature
                ?.let { identifier.identify(it, candidates) }
                ?.takeIf { it.confidence >= DieIdentifier.IDENTITY_CONFIRM_THRESHOLD }
            DieAssignment(
                boundingBox = det.boundingBox,
                value = det.value ?: DEFAULT_VALUE,
                recognizedValue = det.value,
                dieId = guess?.dieId,
                dieIdConfidence = guess?.confidence,
                signature = signature,
            )
        }
        return capAutoAssignments(assignments, registered)
    }

    private fun identityCandidates(dice: List<DieEntity>): List<DieIdentifier.Candidate> =
        dice.mapNotNull { d ->
            d.colorSignature
                ?.let { DieColorSignature.decode(it) }
                ?.let { DieIdentifier.Candidate(d.id, it) }
        }

    /** Sets a die's value, clamped to the assigned die's possible range (d6 until assigned). */
    fun setValue(index: Int, value: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            val die = state.dice.getOrNull(index) ?: return@update state
            val type = die.dieTypeIn(state.registeredDice) ?: DieType.DEFAULT
            val clamped = value.coerceIn(type.minValue, type.maxValue)
            state.copy(
                dice = state.dice.mapIndexed { i, d ->
                    if (i == index) d.copy(value = clamped, edited = true) else d
                },
            )
        }
    }

    /** Drops a detected die the user judges to be a false positive. */
    fun removeDie(index: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            state.copy(dice = state.dice.filterIndexed { i, _ -> i != index })
        }
    }

    fun assignDie(index: Int, dieId: Long) = updateDie(index) { it.copy(dieId = dieId) }

    /** Picks which die the next tapped box will be identified as. */
    fun selectActiveDie(dieId: Long) {
        _uiState.update { if (it is DetectionUiState.Ready) it.copy(activeDieId = dieId) else it }
    }

    /**
     * Identifies the tapped die as the active die from the palette. The active
     * selection stays on a pool until its per-roll capacity is used, then advances
     * to the next non-full die — so tapping boxes in order walks down the palette,
     * lingering on pools (DESIGN.md "Die Pools").
     */
    fun identifyAsActive(index: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            val active = state.activeDieId ?: return@update state
            val assigned = state.dice.mapIndexed { i, die ->
                if (i == index) die.copy(dieId = active) else die
            }
            val counts = assigned.mapNotNull { it.dieId }.groupingBy { it }.eachCount()
            state.copy(
                dice = assigned,
                activeDieId = activeDieAfterAssignment(state.recentDice, active, counts),
            )
        }
    }

    fun registerAndAssign(index: Int, name: String, count: Int = 1) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed, count)
            updateDie(index) { it.copy(dieId = id) }
        }
    }

    /** Registers a new die (or pool, when [count] > 1) from the palette and makes it active. */
    fun registerAndSetActive(name: String, count: Int = 1) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed, count)
            _uiState.update { if (it is DetectionUiState.Ready) it.copy(activeDieId = id) else it }
        }
    }

    fun save() {
        val state = _uiState.value as? DetectionUiState.Ready ?: return
        if (!state.canSave || state.saving) return
        _uiState.update { (it as DetectionUiState.Ready).copy(saving = true) }
        viewModelScope.launch {
            val confirmed = state.dice.map { die ->
                ConfirmedDie(
                    dieId = die.dieId!!,
                    value = die.value,
                    confidence = if (die.recognizedValue == die.value) 1f else 0f,
                    boundingBox = die.boundingBox,
                    wasCorrected = die.recognizedValue != die.value,
                    colorSignature = die.signature,
                )
            }
            repository.saveRoll(currentPhotoPath, System.currentTimeMillis(), confirmed)
            _uiState.update { (it as DetectionUiState.Ready).copy(saving = false, saved = true) }
        }
    }

    /**
     * Files the current photo (with the recognizer's output as metadata) for later
     * analysis, then discards the original capture and signals navigation back to
     * the camera. A development aid for collecting badly-recognized photos.
     */
    fun reportUnrecognized() {
        if (_reported.value) return
        val metadata = buildReportMetadata()
        viewModelScope.launch {
            repository.reportUnrecognized(currentPhotoPath, metadata)
            discardPhoto()
            _reported.value = true
        }
    }

    private fun buildReportMetadata(): String {
        val json = JSONObject()
        json.put("reportedAt", System.currentTimeMillis())
        json.put("photo", File(currentPhotoPath).name)
        when (val state = _uiState.value) {
            is DetectionUiState.Ready -> {
                json.put("aspectRatio", state.aspectRatio.toDouble())
                val dice = JSONArray()
                state.dice.forEach { die ->
                    val box = die.boundingBox
                    val entry = JSONObject()
                    entry.put(
                        "box",
                        JSONArray(listOf(box.left, box.top, box.right, box.bottom)),
                    )
                    entry.put("recognizedValue", die.recognizedValue ?: JSONObject.NULL)
                    entry.put("value", die.value)
                    entry.put("dieIdConfidence", die.dieIdConfidence ?: JSONObject.NULL)
                    dice.put(entry)
                }
                json.put("dice", dice)
            }

            is DetectionUiState.Error -> json.put("error", state.message)
            DetectionUiState.Loading -> json.put("state", "loading")
        }
        return json.toString()
    }

    /** Deletes the captured photo when the user retakes or backs out without saving. */
    fun discardPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(currentPhotoPath).delete() }
        }
    }

    private fun updateDie(index: Int, transform: (DieAssignment) -> DieAssignment) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            state.copy(dice = state.dice.mapIndexed { i, die -> if (i == index) transform(die) else die })
        }
    }

    private fun decodeOriented(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val rotation = exifRotationDegrees(path)
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    /**
     * Decodes [region] (normalized, in display coordinates) from [path] at native
     * resolution via [BitmapRegionDecoder], returning the crop oriented for display.
     * Decoding the sub-rectangle from the original file (rather than cropping the
     * downscaled bitmap) is what recovers detail for small dice.
     */
    private fun decodeRegion(path: String, region: BoundingBox): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        @Suppress("DEPRECATION")
        val decoder = file.inputStream().use { BitmapRegionDecoder.newInstance(it, false) }
            ?: return null
        try {
            val rotation = exifRotationDegrees(path)
            val raw = mapOrientedRegionToRaw(region, decoder.width, decoder.height, rotation)
            val rect = Rect(raw.left, raw.top, raw.right, raw.bottom)
            if (rect.width() <= 0 || rect.height() <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(rect.width(), rect.height(), REGION_TARGET_MAX_EDGE)
            }
            val decoded = decoder.decodeRegion(rect, options) ?: return null

            if (rotation == 0) return decoded
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            return rotated
        } finally {
            decoder.recycle()
        }
    }

    private fun exifRotationDegrees(path: String): Int =
        when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    companion object {
        const val ARG_PHOTO_PATH = "photoPath"
        private const val TARGET_MAX_EDGE = 1280
        private const val REGION_TARGET_MAX_EDGE = 1280
        private const val DEFAULT_VALUE = 1
    }
}
