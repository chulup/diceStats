package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.chulup.dicestats.data.db.DiceDatabase
import xyz.chulup.dicestats.data.eval.ConfirmedBox
import xyz.chulup.dicestats.data.eval.DieOrigin
import xyz.chulup.dicestats.data.eval.scoreRun
import xyz.chulup.dicestats.feature.detection.sampleSizeFor
import java.io.File

/**
 * On-device comparison of the one-stage YOLO models and the two-stage pipelines (one-class
 * detector → full-res crop → value classifier) on every saved roll, against the dice the user
 * confirmed. Uses the bundled assets and the app's own classes ([YoloDieDetector],
 * [TwoStageRecognizer], [DieRecognizer.classifyKnown]), so timings are what the app pays.
 *
 * Ground truth = the roll's stored results. The DB is read from a **copy** (never the live file).
 * The pipelines run interleaved per photo so heat affects all alike; each model's first pass is
 * a warm-up and left out of the timings. Also classifies the dice at their confirmed positions
 * (value reading alone, no detector). Report-only: logcat tag [LOG_TAG] and
 * `files/model-eval/bench-<time>.txt`.
 */
@RunWith(AndroidJUnit4::class)
class TwoStageBenchmarkTest {

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("org.bytedeco.javacpp.cachedir", ctx.cacheDir.absolutePath)
        Loader.load(opencv_core::class.java)
    }

    private class Stats {
        var photos = 0
        var dice = 0
        var found = 0
        var right = 0
        var extra = 0
        val ms = mutableMapOf<String, MutableList<Double>>()
        val misreads = mutableListOf<String>()
        fun time(key: String, v: Double) = ms.getOrPut(key) { mutableListOf() }.add(v)
    }

    @Test
    fun benchmarkPipelinesOnSavedRolls() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rolls = readRolls(ctx)
        val oneStage = YoloDieDetector.MODEL_ASSETS
            .filter { YoloDieDetector.isModelPresent(ctx, it) }
            .map { YoloDieDetector(ctx, it) }
        val dets = TwoStageRecognizer.DETECTOR_ASSETS.associateWith { YoloDieDetector(ctx, it, classValues = emptyList()) }
        val classifiers = TwoStageRecognizer.CLASSIFIER_ASSETS.map { ValueClassifier(ctx, it) }
        val twoStage = dets.values.flatMap { d -> classifiers.map { TwoStageRecognizer(d, it) } }
        val known = DieRecognizer(classifiers = classifiers)

        val stats = linkedMapOf<String, Stats>()
        val knownStats = linkedMapOf<String, Stats>()
        val knownCropMs = mutableListOf<Double>()
        var dice = 0
        for ((index, roll) in rolls.withIndex()) {
            val (path, truth) = roll
            val bitmap = decodeOriented(path) ?: continue
            dice += truth.size
            try {
                val pipelineRuns = runBlocking {
                    oneStage.mapNotNull { it.detectTimed(bitmap) } + twoStage.mapNotNull { it.run(bitmap, path) }
                }
                // The app's 2-of-3 vote: v3 m + both matched two-stage pairs.
                val voterNames = listOf("yolo26m-dice.onnx") +
                    TwoStageRecognizer.DETECTOR_ASSETS.zip(TwoStageRecognizer.CLASSIFIER_ASSETS).map { (d, c) -> "$d+$c" }
                val voterRuns = voterNames.mapNotNull { n -> pipelineRuns.firstOrNull { it.model == n } }
                val runs = if (voterRuns.size == 3) {
                    val agreed = Consensus.vote(voterRuns.map { it.dice })
                    pipelineRuns + voterRuns[0].copy(
                        model = DieRecognizer.CONSENSUS,
                        dice = agreed.map { DetectedDie(value = it.value, boundingBox = it.box) },
                        preprocessMs = voterRuns.sumOf { it.preprocessMs },
                        inferenceMs = voterRuns.sumOf { it.inferenceMs },
                        decodeMs = voterRuns.sumOf { it.decodeMs },
                        cropMs = voterRuns.sumOf { it.cropMs },
                        classifyMs = voterRuns.sumOf { it.classifyMs },
                    )
                } else pipelineRuns
                for (run in runs) {
                    val s = stats.getOrPut(run.model) { Stats() }
                    val score = scoreRun(run.dice, truth)
                    s.photos++
                    s.dice += truth.size
                    s.found += score.found
                    s.right += score.valueCorrect
                    s.extra += score.extra
                    if (index > 0) {
                        s.time("total", run.totalMs)
                        s.time("detect", run.preprocessMs + run.inferenceMs + run.decodeMs)
                        s.time("crop", run.cropMs)
                        s.time("classify", run.classifyMs)
                    }
                }
                val k = runBlocking { known.classifyKnown(path, truth.map { it.box }) } ?: continue
                if (index > 0) knownCropMs.add(k.cropMs / truth.size)
                for (run in k.runs) {
                    val s = knownStats.getOrPut(run.model) { Stats() }
                    s.dice += truth.size
                    run.readings.zip(truth).forEach { (r, c) ->
                        if (r?.value == c.value) s.right++ else s.misreads += "${File(path).name}: ${c.value} read as ${r?.value} (p=%.2f)".format(r?.probability ?: 0f)
                    }
                    if (index > 0) s.time("classify/die", run.classifyMs / truth.size)
                }
            } finally {
                bitmap.recycle()
            }
            Log.i(LOG_TAG, "photo ${index + 1}/${rolls.size} done")
        }

        val report = buildString {
            appendLine("==== Two-stage benchmark: ${rolls.size} saved rolls, $dice confirmed dice (timings exclude the first photo) ====")
            appendLine("%-44s %6s %6s %7s %7s %6s %7s %6s %6s %6s".format(
                "pipeline", "found", "value", "f+right", "extra/p", "ms med", "ms p90", "det", "crop", "cls"))
            for ((name, s) in stats) {
                appendLine("%-44s %6.3f %6.3f %7.3f %7.2f %6.0f %7.0f %6.0f %6.0f %6.0f".format(
                    name, s.found / s.dice.toFloat(), if (s.found > 0) s.right / s.found.toFloat() else 0f,
                    s.right / s.dice.toFloat(), s.extra / s.photos.toFloat(),
                    median(s.ms["total"]), p90(s.ms["total"]), median(s.ms["detect"]), median(s.ms["crop"]), median(s.ms["classify"])))
            }
            appendLine()
            appendLine("value reading at the confirmed positions (crop median %.1f ms/die):".format(median(knownCropMs)))
            for ((name, s) in knownStats) {
                appendLine("  %-28s %d/%d = %.3f, classify median %.1f ms/die".format(
                    name, s.right, s.dice, s.right / s.dice.toFloat(), median(s.ms["classify/die"])))
                s.misreads.forEach { appendLine("      $it") }
            }
        }
        report.trimEnd().split("\n").forEach { Log.i(LOG_TAG, it) }
        println(report)
        val out = File(ctx.getExternalFilesDir(null), "model-eval").apply { mkdirs() }
        File(out, "bench-${System.currentTimeMillis()}.txt").writeText(report)
    }

    /** (photo path, confirmed dice) of every saved roll that still has its photo. */
    private fun readRolls(ctx: android.content.Context): List<Pair<String, List<ConfirmedBox>>> {
        val dir = File(ctx.cacheDir, "bench-db").apply { deleteRecursively(); mkdirs() }
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = ctx.getDatabasePath(DB_NAME + suffix)
            if (f.exists()) f.copyTo(File(dir, DB_NAME + suffix))
        }
        val db = Room.databaseBuilder(ctx, DiceDatabase::class.java, File(dir, DB_NAME).absolutePath)
            .addMigrations(DiceDatabase.MIGRATION_1_2, DiceDatabase.MIGRATION_2_3, DiceDatabase.MIGRATION_3_4, DiceDatabase.MIGRATION_4_5)
            .build()
        try {
            return runBlocking { db.rollDao().observeRollsWithResults().first() }.mapNotNull { r ->
                val path = r.roll.photoPath?.takeIf { File(it).exists() } ?: return@mapNotNull null
                val truth = r.results.mapNotNull { res ->
                    val p = res.boundingBox.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (p.size != 4) null
                    else ConfirmedBox(BoundingBox(p[0], p[1], p[2], p[3]), res.value, null, DieOrigin.DETECTED, false)
                }
                if (truth.isEmpty()) null else path to truth
            }.sortedBy { it.first }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    /** As `DetectionViewModel.decodeOriented`: 1280 px long edge, EXIF-rotated. */
    private fun decodeOriented(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, 1280) },
        ) ?: return null
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (rotation == 0) return decoded
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true).also { if (it != decoded) decoded.recycle() }
    }

    private fun median(v: List<Double>?): Double = v?.sorted()?.let { if (it.isEmpty()) Double.NaN else it[it.size / 2] } ?: Double.NaN
    private fun p90(v: List<Double>?): Double = v?.sorted()?.let { if (it.isEmpty()) Double.NaN else it[minOf(it.size - 1, (it.size * 0.9).toInt())] } ?: Double.NaN

    private companion object {
        const val DB_NAME = "dicestats.db"
        const val LOG_TAG = "TwoStageBench"
    }
}
