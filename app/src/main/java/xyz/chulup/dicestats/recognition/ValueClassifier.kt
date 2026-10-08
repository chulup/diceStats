package xyz.chulup.dicestats.recognition

import android.content.Context
import android.util.Log
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_dnn.Net
import xyz.chulup.dicestats.recognition.internal.ValueClassifierPipeline

/**
 * Stage 2 of the two-stage pipeline: reads a die's top-face value from a full-resolution crop
 * ([DieCropper]) with a small classification model (app asset [assetName]), run through OpenCV's
 * `dnn` module like [YoloDieDetector]. Currently only used for the on-device model comparison.
 */
class ValueClassifier(
    private val context: Context,
    val assetName: String,
) {
    /** One classified crop: the most likely value and its probability. */
    data class Reading(val value: Int, val probability: Float)

    @Volatile private var net: Net? = null
    @Volatile private var loadAttempted = false

    /** Null when the model can't be loaded. Calls are serialized: one [Net] isn't thread-safe. */
    @Synchronized
    fun classify(crop: DieCropper.Crop): Reading? {
        val model = ensureNet() ?: return null
        val rgba = Mat(crop.bitmap.height, crop.bitmap.width, opencv_core.CV_8UC4)
        try {
            crop.bitmap.copyPixelsToBuffer(rgba.data().capacity(crop.bitmap.byteCount.toLong()).asByteBuffer())
            val probs = ValueClassifierPipeline.classify(rgba, crop.padding, model)
            val best = probs.indices.maxByOrNull { probs[it] } ?: return null
            return Reading(value = best + 1, probability = probs[best])
        } finally {
            rgba.release()
        }
    }

    private fun ensureNet(): Net? {
        if (!loadAttempted) {
            loadAttempted = true
            net = runCatching {
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                opencv_dnn.readNetFromONNX(BytePointer(*bytes), bytes.size.toLong()).also {
                    check(!it.isNull && !it.empty()) { "readNetFromONNX returned an empty net" }
                }
            }.onFailure { Log.w(TAG, "classifier '$assetName' not loaded", it) }.getOrNull()
        }
        return net
    }

    private companion object {
        const val TAG = "ValueClassifier"
    }
}

