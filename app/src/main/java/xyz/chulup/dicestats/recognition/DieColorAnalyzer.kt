package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import xyz.chulup.dicestats.recognition.internal.ColorFingerprintPipeline

/**
 * Android adapter that extracts a die's [DieColorSignature] from a [Bitmap] crop,
 * delegating the actual computation to the framework-free
 * [ColorFingerprintPipeline] (mirrors [ClassicalDieDetector]).
 */
class DieColorAnalyzer {

    private val params = ColorFingerprintPipeline.Params()

    /** Fingerprint of the die at [box] in [bitmap], or null if it can't be read. */
    fun signature(bitmap: Bitmap, box: BoundingBox): DieColorSignature? {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return null

        val left = (box.left * width).toInt()
        val top = (box.top * height).toInt()
        val right = (box.right * width).toInt()
        val bottom = (box.bottom * height).toInt()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return ColorFingerprintPipeline.signature(
            pixels, width, height, left, top, right, bottom, params,
        )
    }
}
