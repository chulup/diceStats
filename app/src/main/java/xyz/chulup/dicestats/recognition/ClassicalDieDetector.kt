package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import xyz.chulup.dicestats.recognition.internal.DiceDetectionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Classical-CV die detector (the MVP spike, see DEVPLAN.md step 2).
 *
 * Extracts the bitmap's pixels and hands them to the framework-free
 * [DiceDetectionPipeline], which segments colored die faces by HSV
 * saturation/value and returns one normalized [BoundingBox] per die.
 */
class ClassicalDieDetector : DieDetector {

    private val params = DiceDetectionPipeline.Params()

    override suspend fun detect(bitmap: Bitmap): List<BoundingBox> = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        DiceDetectionPipeline.detect(pixels, width, height, params)
    }
}
