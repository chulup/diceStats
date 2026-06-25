package xyz.chulup.dicestats.feature.detection

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.ClassicalDieDetector
import xyz.chulup.dicestats.recognition.DieDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface DetectionUiState {
    data object Loading : DetectionUiState

    /**
     * @param aspectRatio width / height of the (orientation-corrected) photo, so the
     *   UI can size the image and align the box overlay exactly.
     */
    data class Ready(
        val aspectRatio: Float,
        val boxes: List<BoundingBox>,
    ) : DetectionUiState

    data class Error(val message: String) : DetectionUiState
}

/**
 * Loads a captured photo, runs die detection off the main thread, and exposes
 * the detected boxes for overlay (DEVPLAN.md step 2).
 */
class DetectionViewModel(
    application: Application,
    private val photoPath: String,
    private val detector: DieDetector = ClassicalDieDetector(),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<DetectionUiState>(DetectionUiState.Loading)
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    init {
        detect()
    }

    /** Deletes the captured photo so a rejected roll never reaches the log (retake). */
    fun discardPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(photoPath).delete() }
        }
    }

    private fun detect() {
        viewModelScope.launch {
            _uiState.value = DetectionUiState.Loading
            val result = runCatching {
                val bitmap = withContext(Dispatchers.Default) { decodeOriented(photoPath) }
                    ?: error("Could not decode photo")
                val boxes = detector.detect(bitmap)
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                bitmap.recycle()
                DetectionUiState.Ready(aspectRatio = ratio, boxes = boxes)
            }
            _uiState.value = result.getOrElse {
                DetectionUiState.Error(it.message ?: "Detection failed")
            }
        }
    }

    /** Decodes the JPEG at a memory-friendly size and applies its EXIF rotation. */
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
        private const val TARGET_MAX_EDGE = 1280

        fun factory(photoPath: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                DetectionViewModel(app, photoPath)
            }
        }
    }
}
