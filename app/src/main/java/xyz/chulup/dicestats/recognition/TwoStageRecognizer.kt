package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two-stage recognition, run as a comparison shadow: a one-class [detector] ("die", no value)
 * finds the dice on the downscaled photo, then [classifier] reads each value from a
 * full-resolution crop of the original file. Logged as one [ModelRun] named `det+cls`.
 */
class TwoStageRecognizer(
    private val detector: YoloDieDetector,
    private val classifier: ValueClassifier,
) : DicePipeline {
    override val name: String get() = "${detector.modelName}+${classifier.assetName}"

    /** [bitmap] is the downscaled photo the detector runs on; [photoPath] the full-res original. */
    override suspend fun run(bitmap: Bitmap, photoPath: String): ModelRun? = withContext(Dispatchers.Default) {
        val det = detector.detectTimed(bitmap) ?: return@withContext null
        var cropNs = 0L
        var classifyNs = 0L
        val dice = runCatching {
            DieCropper(photoPath).use { cropper ->
                det.dice.map { die ->
                    val t0 = System.nanoTime()
                    val crop = cropper.crop(die.boundingBox)
                    val t1 = System.nanoTime()
                    val reading = crop?.let { classifier.classify(it) }
                    crop?.bitmap?.recycle()
                    cropNs += t1 - t0
                    classifyNs += System.nanoTime() - t1
                    die.copy(value = reading?.value, valueScore = reading?.probability)
                }
            }
        }.getOrElse { det.dice }
        det.copy(model = name, dice = dice, cropMs = cropNs / 1e6, classifyMs = classifyNs / 1e6)
    }

    companion object {
        /** One-class detector assets ("die"), best first. */
        val DETECTOR_ASSETS = listOf("yolo26s-die.onnx", "yolo26n-die.onnx")

        /** Value classifier assets, paired with [DETECTOR_ASSETS] by position. */
        val CLASSIFIER_ASSETS = listOf("yolo26s-cls-value.onnx", "yolo26n-cls-value.onnx")
    }
}
