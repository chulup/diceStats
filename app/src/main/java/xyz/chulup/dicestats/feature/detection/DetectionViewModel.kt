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
        /** Every die must be assigned and have a valid value before saving (DESIGN.md). */
        val canSave: Boolean
            get() = dice.isNotEmpty() && dice.all { it.dieId != null && it.value in 1..6 }

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
                    DetectionUiState.Ready(
                        photoPath = currentPhotoPath,
                        aspectRatio = ratio,
                        dice = dice,
                        registeredDice = registered,
                        activeDieId = registered.maxByOrNull { it.createdAt }?.id,
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
        val candidates = identityCandidates(registered)
        return detected.map { det ->
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
    }

    private fun identityCandidates(dice: List<DieEntity>): List<DieIdentifier.Candidate> =
        dice.mapNotNull { d ->
            d.colorSignature
                ?.let { DieColorSignature.decode(it) }
                ?.let { DieIdentifier.Candidate(d.id, it) }
        }

    fun setValue(index: Int, value: Int) =
        updateDie(index) { it.copy(value = value.coerceIn(MIN_VALUE, MAX_VALUE), edited = true) }

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
     * Identifies the tapped die as the active die from the palette, then advances
     * the active selection to the next die in the recent list — so tapping boxes
     * in order walks down the palette.
     */
    fun identifyAsActive(index: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            val active = state.activeDieId ?: return@update state
            val assigned = state.dice.mapIndexed { i, die ->
                if (i == index) die.copy(dieId = active) else die
            }
            val order = state.recentDice
            val nextActive = when {
                order.isEmpty() -> null
                else -> {
                    val cur = order.indexOfFirst { it.id == active }
                    order[(cur + 1) % order.size].id
                }
            }
            state.copy(dice = assigned, activeDieId = nextActive)
        }
    }

    fun registerAndAssign(index: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed)
            updateDie(index) { it.copy(dieId = id) }
        }
    }

    /** Registers a new die from the palette and makes it the active selection. */
    fun registerAndSetActive(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed)
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

    private fun sampleSizeFor(width: Int, height: Int, targetMaxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= targetMaxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        const val ARG_PHOTO_PATH = "photoPath"
        private const val TARGET_MAX_EDGE = 1280
        private const val REGION_TARGET_MAX_EDGE = 1280
        private const val MIN_VALUE = 1
        private const val MAX_VALUE = 6
        private const val DEFAULT_VALUE = 1
    }
}
