package xyz.chulup.dicestats.recognition.internal

import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_dnn.Net
import xyz.chulup.dicestats.recognition.BoundingBox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Framework-free YOLO detection core, run through OpenCV's `dnn` module (the bytedeco build,
 * whose natives load on Android **and** the desktop JVM — so this is unit-testable exactly like
 * [DiceDetectionPipeline] / `PipCounter`, and A/B-benchmarkable in `DetectionStatsReport`).
 *
 * Carries no Android types: it takes an RGBA [Mat] plus a loaded [Net] and returns normalized
 * [BoundingBox]es. `YoloDieDetector` wraps it with the on-device asset/`Bitmap` plumbing.
 *
 * ## Model contract
 * An Ultralytics YOLO detection model exported to ONNX (`yolo export format=onnx opset=12`),
 * fixed square input (default 640), RGB, pixel values scaled to 0..1. Two output layouts are
 * handled ([Layout]):
 *  - **RAW** `[1, 4+nc, anchors]` — the default detect export. Rows 0..3 are `cx,cy,w,h` in input
 *    pixels; rows `4..4+nc` are per-class scores (already activated, no separate objectness in
 *    v8+). Needs NMS, which we apply here.
 *  - **END_TO_END** `[1, N, 6]` — an NMS-free export (`nms=True` / the v10+/26 end-to-end head).
 *    Each row is `x1,y1,x2,y2,conf,cls` in input pixels, already de-duplicated.
 *
 * The trained model does not exist yet; when it lands, confirm the real export's output shape and
 * class map against [Params] and this decoder (the geometry is unit-tested in `YoloDecodeTest`).
 */
object YoloDetectionPipeline {

    enum class Layout {
        /** Pick RAW vs END_TO_END from the output shape (few rows ⇒ end-to-end). */
        AUTO,
        RAW,
        END_TO_END,
    }

    data class Params(
        /** Square network input edge, in pixels (the exported model's fixed input size). */
        val inputSize: Int = 640,
        /** Minimum class/confidence score to keep a detection. */
        val confThreshold: Float = 0.25f,
        /** IoU above which overlapping boxes are suppressed (NMS). */
        val iouThreshold: Float = 0.45f,
        /**
         * Class ids to keep, or null for all. The app tells dice types apart via its own model;
         * a single-class ("die") export leaves this null.
         */
        val keepClasses: Set<Int>? = null,
        val layout: Layout = Layout.AUTO,
    )

    /** One decoded detection in normalized-plus-score form, before NMS. */
    private class Candidate(
        val box: BoundingBox,
        val score: Float,
        val classId: Int,
    )

    /**
     * Full pass: letterbox [rgba] into the network's square input, run [net], decode + NMS.
     * Recall-oriented like the classical detector — thresholds stay permissive; the confirm
     * screen prunes false positives.
     */
    fun detect(rgba: Mat, net: Net, params: Params = Params()): List<BoundingBox> {
        val srcW = rgba.cols()
        val srcH = rgba.rows()
        if (srcW == 0 || srcH == 0) return emptyList()

        val rgb = Mat()
        opencv_imgproc.cvtColor(rgba, rgb, opencv_imgproc.COLOR_RGBA2RGB)

        // Letterbox: scale to fit the square input preserving aspect, pad the remainder with 114
        // grey (Ultralytics' convention). Track scale/pad to invert the mapping on the boxes.
        val scale = min(params.inputSize / srcW.toDouble(), params.inputSize / srcH.toDouble())
        val newW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val newH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val padX = (params.inputSize - newW) / 2
        val padY = (params.inputSize - newH) / 2

        val resized = Mat()
        opencv_imgproc.resize(rgb, resized, Size(newW, newH), 0.0, 0.0, opencv_imgproc.INTER_LINEAR)
        val padded = Mat()
        opencv_core.copyMakeBorder(
            resized, padded,
            padY, params.inputSize - newH - padY,
            padX, params.inputSize - newW - padX,
            opencv_core.BORDER_CONSTANT, Scalar(114.0, 114.0, 114.0, 0.0),
        )

        val blob = opencv_dnn.blobFromImage(
            padded, 1.0 / 255.0, Size(params.inputSize, params.inputSize),
            Scalar(0.0, 0.0, 0.0, 0.0), /* swapRB = */ false, /* crop = */ false, opencv_core.CV_32F,
        )

        return try {
            net.setInput(blob)
            val output = net.forward()
            decode(output, srcW, srcH, scale, padX, padY, params)
        } finally {
            rgb.release()
            resized.release()
            padded.release()
            blob.release()
        }
    }

    /**
     * Pure decoder: turn a network [output] Mat into normalized boxes. Split out (and taking the
     * letterbox transform as plain scalars) so the geometry is unit-testable without a model.
     */
    fun decode(
        output: Mat,
        srcW: Int,
        srcH: Int,
        scale: Double,
        padX: Int,
        padY: Int,
        params: Params,
    ): List<BoundingBox> {
        val dims = output.dims()
        // Content dims are the trailing two; a leading unit batch dim (dims == 3) is prefixed 0.
        val d1 = output.size(dims - 2)
        val d2 = output.size(dims - 1)
        // The feature axis (4+nc, or 6) is the short one; anchors/detections is the long one.
        val featuresAlongFirst = d1 <= d2
        val featureCount = if (featuresAlongFirst) d1 else d2
        val rowCount = if (featuresAlongFirst) d2 else d1

        val endToEnd = when (params.layout) {
            Layout.RAW -> false
            Layout.END_TO_END -> true
            // Auto: an end-to-end head emits few rows (~300); a raw grid emits thousands.
            Layout.AUTO -> featureCount == END_TO_END_FEATURES && rowCount <= MAX_END_TO_END_ROWS
        }

        val indexer = output.createIndexer<FloatIndexer>(true)
        val candidates = ArrayList<Candidate>()
        try {
            fun at(feature: Int, row: Int): Float {
                val i0 = if (featuresAlongFirst) feature else row
                val i1 = if (featuresAlongFirst) row else feature
                return if (dims >= 3) indexer.get(0L, i0.toLong(), i1.toLong())
                else indexer.get(i0.toLong(), i1.toLong())
            }

            for (row in 0 until rowCount) {
                val classId: Int
                val score: Float
                val cx: Float
                val cy: Float
                val w: Float
                val h: Float
                if (endToEnd) {
                    score = at(4, row)
                    if (score < params.confThreshold) continue
                    classId = at(5, row).roundToInt()
                    val x1 = at(0, row); val y1 = at(1, row); val x2 = at(2, row); val y2 = at(3, row)
                    cx = (x1 + x2) / 2f; cy = (y1 + y2) / 2f; w = x2 - x1; h = y2 - y1
                } else {
                    var best = -1
                    var bestScore = 0f
                    for (c in 0 until featureCount - 4) {
                        val s = at(4 + c, row)
                        if (s > bestScore) { bestScore = s; best = c }
                    }
                    if (bestScore < params.confThreshold) continue
                    classId = best
                    score = bestScore
                    cx = at(0, row); cy = at(1, row); w = at(2, row); h = at(3, row)
                }
                if (params.keepClasses != null && classId !in params.keepClasses) continue

                val box = toNormalized(cx, cy, w, h, srcW, srcH, scale, padX, padY) ?: continue
                candidates.add(Candidate(box, score, classId))
            }
        } finally {
            indexer.release()
        }
        return nonMaxSuppression(candidates, params.iouThreshold)
    }

    /**
     * Undo the letterbox (subtract pad, divide by scale) and normalize to 0..1 fractions of the
     * source image. Returns null for degenerate boxes that clip to nothing.
     */
    private fun toNormalized(
        cx: Float, cy: Float, w: Float, h: Float,
        srcW: Int, srcH: Int, scale: Double, padX: Int, padY: Int,
    ): BoundingBox? {
        val left = ((cx - w / 2f - padX) / scale / srcW).toFloat().coerceIn(0f, 1f)
        val top = ((cy - h / 2f - padY) / scale / srcH).toFloat().coerceIn(0f, 1f)
        val right = ((cx + w / 2f - padX) / scale / srcW).toFloat().coerceIn(0f, 1f)
        val bottom = ((cy + h / 2f - padY) / scale / srcH).toFloat().coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return BoundingBox(left, top, right, bottom)
    }

    /** Greedy IoU non-max suppression, highest score first. Harmless (a no-op) on end-to-end output. */
    private fun nonMaxSuppression(candidates: List<Candidate>, iouThreshold: Float): List<BoundingBox> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Candidate>()
        for (cand in sorted) {
            if (kept.none { iou(it.box, cand.box) > iouThreshold }) kept.add(cand)
        }
        return kept.map { it.box }
    }

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private const val END_TO_END_FEATURES = 6
    private const val MAX_END_TO_END_ROWS = 1024
}
