package xyz.chulup.dicestats.recognition.internal

import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_dnn.Net
import kotlin.math.exp

/**
 * Framework-free core of the die-value classifier (stage 2 of the two-stage pipeline): one die
 * crop in, the probability of each top-face value out.
 *
 * ## Crop rule (must match training, `../training/two_stage_data.py` `crop()`)
 * A square around the die's box center, side = [CROP_SCALE] × the box's longer edge, with
 * replicate padding where it leaves the photo, cut from the **full-resolution** photo.
 * [squareAround] computes it; the caller decodes the inside part and passes the padding here.
 *
 * ## Model contract
 * Ultralytics classify export to ONNX (opset 12): input `[1,3,S,S]` RGB 0..1 (S = 224), output
 * `[1,6]` softmax over classes `1`..`6` (class id = value − 1).
 */
object ValueClassifierPipeline {

    const val CROP_SCALE = 1.3f
    const val INPUT_SIZE = 224

    /** Pixels of replicate padding on each side, for the part of the crop outside the photo. */
    data class Padding(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    /** A square crop in pixel coordinates of an image; may extend past its edges. */
    data class Square(val left: Int, val top: Int, val side: Int) {
        val right: Int get() = left + side
        val bottom: Int get() = top + side

        /** The part inside a [width]×[height] image, and the padding that restores the square. */
        fun clipTo(width: Int, height: Int): Pair<IntArray, Padding> {
            val l = left.coerceIn(0, width)
            val t = top.coerceIn(0, height)
            val r = right.coerceIn(0, width)
            val b = bottom.coerceIn(0, height)
            return intArrayOf(l, t, r, b) to Padding(l - left, t - top, right - r, bottom - b)
        }
    }

    /** The training crop square for a box given in pixels (left, top, right, bottom). */
    fun squareAround(left: Float, top: Float, right: Float, bottom: Float): Square {
        val side = maxOf(2, Math.round(maxOf(right - left, bottom - top) * CROP_SCALE))
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return Square(Math.round(cx - side / 2f), Math.round(cy - side / 2f), side)
    }

    /**
     * Pads [rgba] (the decoded inside part of the crop, scaled by the same factor as [padding]),
     * resizes to the network input and returns per-class probabilities.
     */
    fun classify(rgba: Mat, padding: Padding, net: Net): FloatArray {
        val rgb = Mat()
        val padded = Mat()
        val resized = Mat()
        try {
            opencv_imgproc.cvtColor(rgba, rgb, opencv_imgproc.COLOR_RGBA2RGB)
            opencv_core.copyMakeBorder(
                rgb, padded, padding.top, padding.bottom, padding.left, padding.right,
                opencv_core.BORDER_REPLICATE,
            )
            // Training stored crops shrunk with INTER_AREA; small ones were upscaled bilinearly.
            val interpolation = if (padded.cols() > INPUT_SIZE) opencv_imgproc.INTER_AREA else opencv_imgproc.INTER_LINEAR
            opencv_imgproc.resize(padded, resized, Size(INPUT_SIZE, INPUT_SIZE), 0.0, 0.0, interpolation)
            val blob = opencv_dnn.blobFromImage(
                resized, 1.0 / 255.0, Size(INPUT_SIZE, INPUT_SIZE),
                Scalar(0.0, 0.0, 0.0, 0.0), /* swapRB = */ false, /* crop = */ false, opencv_core.CV_32F,
            )
            try {
                net.setInput(blob)
                return probabilities(net.forward())
            } finally {
                blob.release()
            }
        } finally {
            rgb.release()
            padded.release()
            resized.release()
        }
    }

    /** Reads a `[1,N]` output; softmaxes it unless it already is a distribution. */
    fun probabilities(output: Mat): FloatArray {
        val n = output.size(output.dims() - 1)
        val indexer = output.createIndexer<FloatIndexer>(true)
        val raw = try {
            FloatArray(n) { if (output.dims() >= 2) indexer.get(0L, it.toLong()) else indexer.get(it.toLong()) }
        } finally {
            indexer.release()
        }
        val sum = raw.sum()
        if (raw.all { it >= 0f } && sum in 0.99f..1.01f) return raw
        val max = raw.max()
        val e = raw.map { exp((it - max).toDouble()).toFloat() }
        val total = e.sum()
        return FloatArray(n) { e[it] / total }
    }
}
