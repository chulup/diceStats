package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import org.bytedeco.javacpp.indexer.DoubleIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.Size
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Flags a die whose top face is likely too blurry to read: logistic regression over
 * contrast-normalized sharpness features of the die crop, trained on dice a reviewer marked
 * readable / too blurry (`../training/blur_app_model.py`, grouped-CV ROC-AUC ≈ 0.92).
 *
 * The features must match the training script exactly — crop with a 15% margin from the
 * working bitmap (the 1280px-sampled photo `DetectionViewModel` recognizes on), resize to
 * 128×128 (INTER_AREA), grey, keep the inner 80%, then
 *  - `tenengrad` — mean Sobel gradient energy E[gx² + gy²]
 *  - `norm_grad` — mean gradient magnitude / grey std (edge steepness, contrast-free)
 *  - `aniso` — min/max directional gradient energy over 8 directions (motion blur → low)
 *  - `log_die_px` — log of the die's longer side in working pixels.
 * Coefficients: [BlurModel.DEFAULT], from the script's `app_model.json`.
 *
 * Like [PipCounter], the [Mat] core carries no Android types so JVM tests can check parity
 * with the Python features.
 */
class BlurDetector(private val model: BlurModel = BlurModel.DEFAULT) {

    data class Features(val tenengrad: Double, val normGrad: Double, val aniso: Double)

    /** Probability (0..1) that the die in [box] of the working [bitmap] is too blurry to read. */
    fun probability(bitmap: Bitmap, box: BoundingBox): Float {
        val rgba = Mat(bitmap.height, bitmap.width, opencv_core.CV_8UC4)
        bitmap.copyPixelsToBuffer(rgba.data().capacity(bitmap.byteCount.toLong()).asByteBuffer())
        return try {
            probability(rgba, box)
        } finally {
            rgba.release()
        }
    }

    /** [probability] on an RGBA (or RGB) [Mat] of the working image. */
    fun probability(image: Mat, box: BoundingBox): Float {
        val w = image.cols()
        val h = image.rows()
        val l = box.left * w
        val t = box.top * h
        val r = box.right * w
        val b = box.bottom * h
        val mx = MARGIN * (r - l)
        val my = MARGIN * (b - t)
        // Same integer rect as the training script: truncate, clamp, at least 1 px.
        val x0 = max(0, (l - mx).toInt()).coerceAtMost(w - 1)
        val y0 = max(0, (t - my).toInt()).coerceAtMost(h - 1)
        val x1 = min(w, max(x0 + 1, (r + mx).toInt()))
        val y1 = min(h, max(y0 + 1, (b + my).toInt()))
        val crop = Mat(image, Rect(x0, y0, x1 - x0, y1 - y0))
        return try {
            model.probability(features(crop), max(r - l, b - t).toDouble())
        } finally {
            crop.release()
        }
    }

    fun isBlurry(probability: Float): Boolean = probability >= model.threshold

    companion object {
        private const val MARGIN = 0.15f
        private const val SIZE = 128
        private const val INNER = 12 // drop 12 px per side of the 128 px crop = inner 80%
        private const val DIRECTIONS = 8

        /** Features of a margin crop (RGBA or RGB [Mat], any size). */
        fun features(crop: Mat): Features {
            val resized = Mat()
            opencv_imgproc.resize(crop, resized, Size(SIZE, SIZE), 0.0, 0.0, opencv_imgproc.INTER_AREA)
            val grey8 = Mat()
            val code = if (resized.channels() == 4) opencv_imgproc.COLOR_RGBA2GRAY else opencv_imgproc.COLOR_RGB2GRAY
            opencv_imgproc.cvtColor(resized, grey8, code)
            val greyFull = Mat()
            grey8.convertTo(greyFull, opencv_core.CV_32F)
            // Copy the inner region so Sobel's border handling sees it as the whole image (as numpy slicing does).
            val grey = Mat(greyFull, Rect(INNER, INNER, SIZE - 2 * INNER, SIZE - 2 * INNER)).clone()

            val gx = Mat()
            val gy = Mat()
            opencv_imgproc.Sobel(grey, gx, opencv_core.CV_32F, 1, 0)
            opencv_imgproc.Sobel(grey, gy, opencv_core.CV_32F, 0, 1)
            val exx = meanOfProduct(gx, gx)
            val eyy = meanOfProduct(gy, gy)
            val exy = meanOfProduct(gx, gy)
            val mag = Mat()
            opencv_core.magnitude(gx, gy, mag)
            val meanMag = opencv_core.mean(mag).get(0)
            val mean = Mat()
            val std = Mat()
            opencv_core.meanStdDev(grey, mean, std)
            val sd = std.createIndexer<DoubleIndexer>().use { it.get(0L) }

            var lo = Double.MAX_VALUE
            var hi = 0.0
            for (k in 0 until DIRECTIONS) {
                val a = k * Math.PI / DIRECTIONS
                val e = cos(a) * cos(a) * exx + sin(a) * sin(a) * eyy + 2 * sin(a) * cos(a) * exy
                lo = min(lo, e)
                hi = max(hi, e)
            }
            listOf(resized, grey8, greyFull, grey, gx, gy, mag, mean, std).forEach { it.release() }
            return Features(
                tenengrad = exx + eyy,
                normGrad = meanMag / (sd + 1e-3),
                aniso = lo / (hi + 1e-9),
            )
        }

        private fun meanOfProduct(a: Mat, b: Mat): Double {
            val p = Mat()
            opencv_core.multiply(a, b, p)
            return opencv_core.mean(p).get(0).also { p.release() }
        }
    }
}

/**
 * Coefficients of the blur logistic regression, from `../training/blur_app_model.py`
 * (`app_model.json`; a copy in test resources `blur/model.json` keeps [DEFAULT] in sync via
 * `BlurDetectorTest`): x = [log1p(tenengrad), log1p(norm_grad), log1p(aniso), log(die_px)],
 * standardized by [mean] / [scale], p = sigmoid(weights · z + bias). [threshold] is the default
 * operating point: ≈5% of readable dice flagged, ≈59% of too-blurry dice caught.
 */
data class BlurModel(
    val mean: List<Double>,
    val scale: List<Double>,
    val weights: List<Double>,
    val bias: Double,
    val threshold: Double,
) {
    fun probability(f: BlurDetector.Features, diePx: Double): Float {
        val x = doubleArrayOf(ln1p(f.tenengrad), ln1p(f.normGrad), ln1p(f.aniso), ln(max(diePx, 1.0)))
        var z = bias
        for (i in x.indices) z += weights[i] * (x[i] - mean[i]) / scale[i]
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    companion object {
        /** Trained 2026-10-08 on 2396 reviewed dice (548 too blurry); grouped-CV ROC-AUC 0.9193. */
        val DEFAULT = BlurModel(
            mean = listOf(8.241680784930022, 0.730959943955248, 0.4256054386989092, 4.796484005573654),
            scale = listOf(0.9043733304835958, 0.12536847398090223, 0.13806143758991377, 0.4806689534290246),
            weights = listOf(-0.8200712316250643, -0.8987959974852868, -1.4817614928567329, -0.8531923865903808),
            bias = -2.629734011119208,
            threshold = 0.5533,
        )
    }
}
