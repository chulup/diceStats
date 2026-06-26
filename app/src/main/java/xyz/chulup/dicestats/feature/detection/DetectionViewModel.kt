package xyz.chulup.dicestats.feature.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.ConfirmedDie
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** One detected die on the confirm screen: editable value + chosen registered die. */
data class DieAssignment(
    val boundingBox: BoundingBox,
    val value: Int,
    val recognizedValue: Int?,
    val dieId: Long?,
)

sealed interface DetectionUiState {
    data object Loading : DetectionUiState

    data class Ready(
        val aspectRatio: Float,
        val dice: List<DieAssignment>,
        val registeredDice: List<DieEntity>,
        val saving: Boolean = false,
        val saved: Boolean = false,
    ) : DetectionUiState {
        /** Every die must be assigned and have a valid value before saving (DESIGN.md). */
        val canSave: Boolean
            get() = dice.isNotEmpty() && dice.all { it.dieId != null && it.value in 1..6 }
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

    private val photoPath: String =
        savedStateHandle.get<String>(ARG_PHOTO_PATH) ?: error("photoPath argument required")

    private val recognizer = DieRecognizer()

    private val registeredDice = repository.dice
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<DetectionUiState>(DetectionUiState.Loading)
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    init {
        recognize()
        // Keep the die picker in sync as dice get registered.
        viewModelScope.launch {
            registeredDice.collect { dice ->
                _uiState.update { if (it is DetectionUiState.Ready) it.copy(registeredDice = dice) else it }
            }
        }
    }

    private fun recognize() {
        viewModelScope.launch {
            _uiState.value = DetectionUiState.Loading
            val result = runCatching {
                val bitmap = withContext(Dispatchers.Default) { decodeOriented(photoPath) }
                    ?: error("Could not decode photo")
                val detected = recognizer.recognize(bitmap)
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                bitmap.recycle()
                ratio to detected.map {
                    DieAssignment(
                        boundingBox = it.boundingBox,
                        value = it.value ?: DEFAULT_VALUE,
                        recognizedValue = it.value,
                        dieId = null,
                    )
                }
            }
            _uiState.value = result.fold(
                onSuccess = { (ratio, dice) ->
                    DetectionUiState.Ready(ratio, dice, registeredDice.value)
                },
                onFailure = { DetectionUiState.Error(it.message ?: "Detection failed") },
            )
        }
    }

    fun setValue(index: Int, value: Int) =
        updateDie(index) { it.copy(value = value.coerceIn(MIN_VALUE, MAX_VALUE)) }

    /** Drops a detected die the user judges to be a false positive. */
    fun removeDie(index: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            state.copy(dice = state.dice.filterIndexed { i, _ -> i != index })
        }
    }

    fun assignDie(index: Int, dieId: Long) = updateDie(index) { it.copy(dieId = dieId) }

    fun registerAndAssign(index: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed)
            updateDie(index) { it.copy(dieId = id) }
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
                )
            }
            repository.saveRoll(photoPath, System.currentTimeMillis(), confirmed)
            _uiState.update { (it as DetectionUiState.Ready).copy(saving = false, saved = true) }
        }
    }

    /** Deletes the captured photo when the user retakes or backs out without saving. */
    fun discardPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(photoPath).delete() }
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
        private const val MIN_VALUE = 1
        private const val MAX_VALUE = 6
        private const val DEFAULT_VALUE = 1
    }
}
