package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.KeyPointVector
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_features2d.SimpleBlobDetector

/**
 * Counts the pips on a detected die face using OpenCV blob detection
 * (the Golsteyn approach, DESIGN.md "OpenCV for pip counting").
 *
 * Pips are near-circular blobs. Dice come in either polarity here (white pips on
 * red dice, dark pips on yellow dice), so we detect both dark and light blobs and
 * merge them. Counting on the full-resolution crop keeps small pips sharp.
 *
 * OpenCV is the bytedeco/JavaCPP build, whose natives load both on Android and on the
 * desktop JVM — so the [Mat]-based [count] core is shared by the on-device [Bitmap]
 * path and JVM unit tests. The native library is loaded in `DiceStatsApp` (device) /
 * the test's `@BeforeClass` (JVM) via `Loader.load`.
 */
class PipCounter {

    /**
     * @return the pip value 1..6, or null when the blob count falls outside that
     *   range (unreadable face, or a non-die region with no pips).
     */
    fun count(bitmap: Bitmap, box: BoundingBox): Int? {
        // Copy the bitmap's pixels straight into the Mat's buffer: ARGB_8888 is stored
        // R,G,B,A in memory, matching CV_8UC4, so Bitmap's own copyPixelsToBuffer suffices.
        val rgba = Mat(bitmap.height, bitmap.width, opencv_core.CV_8UC4)
        bitmap.copyPixelsToBuffer(rgba.data().capacity(bitmap.byteCount.toLong()).asByteBuffer())
        return try {
            count(rgba, box)
        } finally {
            rgba.release()
        }
    }

    /**
     * Pip-counting core on an RGBA [rgba] Mat (4-channel, R,G,B,A). Carries no Android
     * types, so it runs in JVM unit tests fed a Mat built from raw pixels — the same path
     * the [Bitmap] overload takes on-device.
     */
    fun count(rgba: Mat, box: BoundingBox): Int? {
        val w = rgba.cols()
        val h = rgba.rows()

        val left = (box.left * w).toInt().coerceIn(0, w - 1)
        val top = (box.top * h).toInt().coerceIn(0, h - 1)
        val right = (box.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (box.bottom * h).toInt().coerceIn(top + 1, h)
        val rect = Rect(left, top, right - left, bottom - top)

        val crop = Mat(rgba, rect)
        // Normalize the crop to a canonical size so pips are a consistent scale
        // regardless of how near/far the die was — fixed blob-area params then work
        // for both close-up and far-away dice.
        val canonical = Mat()
        val scale = CANONICAL_EDGE / maxOf(rect.width(), rect.height()).toDouble()
        opencv_imgproc.resize(crop, canonical, Size(), scale, scale, opencv_imgproc.INTER_CUBIC)

        val gray = Mat()
        opencv_imgproc.cvtColor(canonical, gray, opencv_imgproc.COLOR_RGBA2GRAY)

        val cropArea = (canonical.rows() * canonical.cols()).toDouble()
        val count = countBlobs(gray, cropArea)

        crop.release()
        canonical.release()
        gray.release()

        return if (count in MIN_PIPS..MAX_PIPS) count else null
    }

    /** A merged pip keypoint: center x/y and diameter, in canonical-crop pixels. */
    private class Pip(val x: Float, val y: Float, val size: Float)

    private fun countBlobs(gray: Mat, cropArea: Double): Int {
        val merged = ArrayList<Pip>()
        for (blobColor in intArrayOf(DARK, LIGHT)) {
            val detector = SimpleBlobDetector.create(paramsFor(blobColor, cropArea))
            val keypoints = KeyPointVector()
            detector.detect(gray, keypoints)
            var i = 0L
            while (i < keypoints.size()) {
                val kp = keypoints.get(i)
                val x = kp.pt().x()
                val y = kp.pt().y()
                val size = kp.size()
                // De-duplicate blobs found by both polarities.
                val isNew = merged.none { existing ->
                    val dx = existing.x - x
                    val dy = existing.y - y
                    val r = DEDUP_FACTOR.toFloat() * size
                    dx * dx + dy * dy <= r * r
                }
                if (isNew) merged.add(Pip(x, y, size))
                i++
            }
            keypoints.close()
            detector.close()
        }
        return merged.size
    }

    private fun paramsFor(blobColor: Int, cropArea: Double): SimpleBlobDetector.Params =
        SimpleBlobDetector.Params().apply {
            filterByColor(true)
            blobColor(blobColor.toByte())
            filterByArea(true)
            minArea((cropArea * MIN_PIP_AREA_FRACTION).toFloat())
            maxArea((cropArea * MAX_PIP_AREA_FRACTION).toFloat())
            filterByCircularity(true)
            minCircularity(0.6f)
            filterByConvexity(true)
            minConvexity(0.7f)
            filterByInertia(true)
            minInertiaRatio(0.4f)
        }

    private companion object {
        const val MIN_PIPS = 1
        const val MAX_PIPS = 6
        const val CANONICAL_EDGE = 256.0
        const val DARK = 0
        const val LIGHT = 255
        const val MIN_PIP_AREA_FRACTION = 0.004
        const val MAX_PIP_AREA_FRACTION = 0.10
        const val DEDUP_FACTOR = 0.6
    }
}
