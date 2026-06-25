package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Rect
import org.opencv.features2d.SimpleBlobDetector
import org.opencv.features2d.SimpleBlobDetector_Params
import org.opencv.imgproc.Imgproc

/**
 * Counts the pips on a detected die face using OpenCV blob detection
 * (the Golsteyn approach, DESIGN.md "OpenCV for pip counting").
 *
 * Pips are near-circular blobs. Dice come in either polarity here (white pips on
 * red dice, dark pips on yellow dice), so we detect both dark and light blobs and
 * merge them. Counting on the full-resolution crop keeps small pips sharp.
 *
 * Requires OpenCV to be initialized (see `DiceStatsApp`).
 */
class PipCounter {

    /**
     * @return the pip value 1..6, or null when the blob count falls outside that
     *   range (unreadable face, or a non-die region with no pips).
     */
    fun count(bitmap: Bitmap, box: BoundingBox): Int? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val w = bitmap.width
        val h = bitmap.height

        val left = (box.left * w).toInt().coerceIn(0, w - 1)
        val top = (box.top * h).toInt().coerceIn(0, h - 1)
        val right = (box.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (box.bottom * h).toInt().coerceIn(top + 1, h)
        val rect = Rect(left, top, right - left, bottom - top)

        val crop = Mat(rgba, rect)
        val gray = Mat()
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)

        val cropArea = (rect.width * rect.height).toDouble()
        val count = countBlobs(gray, cropArea)

        rgba.release()
        crop.release()
        gray.release()

        return if (count in MIN_PIPS..MAX_PIPS) count else null
    }

    private fun countBlobs(gray: Mat, cropArea: Double): Int {
        val merged = ArrayList<KeyPoint>()
        for (blobColor in intArrayOf(DARK, LIGHT)) {
            val detector = SimpleBlobDetector.create(paramsFor(blobColor, cropArea))
            val keypoints = MatOfKeyPoint()
            detector.detect(gray, keypoints)
            for (kp in keypoints.toArray()) {
                // De-duplicate blobs found by both polarities.
                val isNew = merged.none { existing ->
                    val dx = existing.pt.x - kp.pt.x
                    val dy = existing.pt.y - kp.pt.y
                    val r = DEDUP_FACTOR * kp.size
                    dx * dx + dy * dy <= r * r
                }
                if (isNew) merged.add(kp)
            }
            keypoints.release()
        }
        return merged.size
    }

    private fun paramsFor(blobColor: Int, cropArea: Double): SimpleBlobDetector_Params =
        SimpleBlobDetector_Params().apply {
            set_filterByColor(true)
            set_blobColor(blobColor.toByte())
            set_filterByArea(true)
            set_minArea((cropArea * MIN_PIP_AREA_FRACTION).toFloat())
            set_maxArea((cropArea * MAX_PIP_AREA_FRACTION).toFloat())
            set_filterByCircularity(true)
            set_minCircularity(0.6f)
            set_filterByConvexity(true)
            set_minConvexity(0.7f)
            set_filterByInertia(true)
            set_minInertiaRatio(0.4f)
        }

    private companion object {
        const val MIN_PIPS = 1
        const val MAX_PIPS = 6
        const val DARK = 0
        const val LIGHT = 255
        const val MIN_PIP_AREA_FRACTION = 0.004
        const val MAX_PIP_AREA_FRACTION = 0.10
        const val DEDUP_FACTOR = 0.6
    }
}
