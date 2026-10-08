package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.opencv_core.Mat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chulup.dicestats.feature.detection.sampleSizeFor
import xyz.chulup.dicestats.recognition.internal.YoloDetectionPipeline
import java.io.File

/**
 * On-device A/B benchmark of candidate YOLO models on labelled frames, without shipping either in
 * the APK. Push the data to the app's external files dir first:
 *
 *     adb push labels.json frames/ yolo26n-dice.onnx yolo26s-dice.onnx \
 *         /sdcard/Android/data/xyz.chulup.dicestats/files/yolo-eval/
 *
 * `labels.json`: `{"frames": [{"image": "x.jpg", "dice": [{"value": 6, "color": "red",
 * "box": [left, top, right, bottom]}]}]}` with normalized boxes (`../training`, e.g. frames
 * the model never saw). Every `*.onnx` in the folder is run through [YoloDetectionPipeline] on
 * each frame, decoded like `DetectionViewModel` (1280px long edge). Scorecard per model, in the
 * terms of [DbBenchmarkTest]: recall, value accuracy, extra detections per image, plus latency
 * (median / p90 of the pipeline call, first frame excluded as warm-up) and value confusions.
 *
 * **Report-only**: always passes; the scorecard goes to logcat (tag [LOG_TAG]), stdout and
 * `yolo-eval/results.txt`.
 */
@RunWith(AndroidJUnit4::class)
class YoloModelBenchmarkTest {

    private data class LabelsFile(val frames: List<Frame>)
    private data class Frame(val image: String, val dice: List<Die>)
    private data class Die(val value: Int, val color: String, val box: List<Float>)

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("org.bytedeco.javacpp.cachedir", ctx.cacheDir.absolutePath)
        Loader.load(opencv_core::class.java)
    }

    @Test
    fun benchmarkModelsOnLabelledFrames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), EVAL_DIR)
        val labels = File(dir, "labels.json")
        if (!labels.exists()) {
            Log.w(LOG_TAG, "no ${labels.path}; push the eval data first (see class doc)")
            return
        }
        val frames = Gson().fromJson(labels.readText(), LabelsFile::class.java).frames
        val models = dir.listFiles { f -> f.name.endsWith(".onnx") }.orEmpty().sortedBy { it.name }

        val report = buildString {
            appendLine("==== YOLO model benchmark: ${frames.size} frames, ${frames.sumOf { it.dice.size }} dice ====")
            for (model in models) append(benchmark(model, frames, dir))
        }
        report.trimEnd().split("\n").forEach { Log.i(LOG_TAG, it) }
        println(report)
        File(dir, "results.txt").writeText(report)
    }

    private fun benchmark(model: File, frames: List<Frame>, dir: File): String {
        val net = opencv_dnn.readNetFromONNX(model.absolutePath)
        var totalDice = 0
        var covered = 0
        var valueCorrect = 0
        val extraBuckets = IntArray(4)
        val millis = mutableListOf<Long>()
        val confusions = mutableMapOf<String, Int>()
        val perColor = mutableMapOf<String, IntArray>() // color → [dice, value correct]

        for ((i, frame) in frames.withIndex()) {
            val bitmap = decode(File(dir, "frames/${frame.image}")) ?: continue
            val rgba = Mat(bitmap.height, bitmap.width, opencv_core.CV_8UC4)
            bitmap.copyPixelsToBuffer(rgba.data().capacity(bitmap.byteCount.toLong()).asByteBuffer())
            val t0 = SystemClock.elapsedRealtime()
            val detected = YoloDetectionPipeline.detect(rgba, net)
            if (i > 0) millis += SystemClock.elapsedRealtime() - t0
            rgba.release()
            bitmap.recycle()

            val truth = frame.dice.map { BoundingBox(it.box[0], it.box[1], it.box[2], it.box[3]) to it }
            // Greedy 1:1 matching by IoU, best pairs first (as DbBenchmarkTest).
            val claimed = BooleanArray(detected.size)
            val matchOf = IntArray(truth.size) { -1 }
            truth.indices.flatMap { g -> detected.indices.map { d -> Triple(g, d, iou(truth[g].first, detected[d].box)) } }
                .filter { it.third >= IOU_THRESHOLD }.sortedByDescending { it.third }
                .forEach { (g, d, _) ->
                    if (matchOf[g] == -1 && !claimed[d]) {
                        matchOf[g] = d
                        claimed[d] = true
                    }
                }
            for (g in truth.indices) {
                val die = truth[g].second
                val stats = perColor.getOrPut(die.color) { IntArray(2) }
                totalDice++
                stats[0]++
                val d = matchOf[g]
                if (d < 0) continue
                covered++
                val read = YoloDieDetector.CLASS_VALUES.getOrNull(detected[d].classId)
                if (read == die.value) {
                    valueCorrect++
                    stats[1]++
                } else {
                    confusions.merge("${die.value}→$read", 1, Int::plus)
                }
            }
            extraBuckets[claimed.count { !it }.coerceAtMost(3)]++
        }
        net.close()

        val images = extraBuckets.sum()
        millis.sort()
        return buildString {
            appendLine("--- ${model.name} (${model.length() / 1_000_000} MB) ---")
            appendLine("latency (pipeline)    : median ${millis.getOrNull(millis.size / 2)} ms, p90 ${millis.getOrNull(millis.size * 9 / 10)} ms")
            appendLine("recall (dice covered) : $covered/$totalDice = ${"%.1f".format(pct(covered, totalDice))}%")
            appendLine("value accuracy (all)  : $valueCorrect/$totalDice = ${"%.1f".format(pct(valueCorrect, totalDice))}%")
            appendLine("value accuracy (cov.) : $valueCorrect/$covered = ${"%.1f".format(pct(valueCorrect, covered))}%")
            appendLine("extra detections/image: 0: ${extraBuckets[0]}  1: ${extraBuckets[1]}  2: ${extraBuckets[2]}  3+: ${extraBuckets[3]}  (of $images)")
            appendLine("value accuracy by color: " + perColor.toSortedMap().entries.joinToString("  ") { (c, s) -> "$c ${s[1]}/${s[0]}" })
            appendLine("misreads (true→read)  : " + confusions.entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key} ×${it.value}" })
        }
    }

    /** Decoded like `DetectionViewModel`: downscaled to a 1280px long edge (frames carry no EXIF rotation). */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_MAX_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun pct(n: Int, d: Int): Float = if (d == 0) 0f else 100f * n / d

    private fun iou(a: BoundingBox, b: BoundingBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private companion object {
        const val LOG_TAG = "YoloModelBenchmark"
        const val EVAL_DIR = "yolo-eval"
        const val TARGET_MAX_EDGE = 1280
        const val IOU_THRESHOLD = 0.5f
    }
}
