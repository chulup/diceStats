package xyz.chulup.dicestats.recognition

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * On-device benchmark over the device-captured roll dataset (`assets/dbphotos/`),
 * whose ground truth was exported from the app's own database and hand-verified
 * (103 photos, 206 pip-d6 dice; one bogus board-art label was dropped). It runs
 * the real [DieRecognizer] and prints a **scorecard** measuring the three targets
 * we want every recognition algorithm to hold to:
 *
 *  - **Detection recall** — every labelled die is covered by a detection
 *    (target: 100%).
 *  - **Value accuracy** — the covering detection reads the correct pip value
 *    (target: ≥80% of dice).
 *  - **False positives** — extra (unlabelled) detections per image, allowed
 *    within a budget: ≤20% of images may have 1 extra, ≤5% may have 2 extra,
 *    and none should have 3+.
 *
 * This is **report-only**: it always passes and logs the scorecard (tag
 * [LOG_TAG]) so the numbers can be tracked as the pipeline evolves. Tighten it
 * into assertions once the pipeline meets the bar. Ground truth and the
 * normalized box convention match `PipRecognitionTest` / `tests.txt`.
 */
@RunWith(AndroidJUnit4::class)
class DbBenchmarkTest {

    private data class TestsFile(val tests: List<PhotoCase>)
    private data class PhotoCase(val picture_name: String, val dice: List<DieSpec>)
    private data class DieSpec(val boundingBox: Box?, val value: Int, val color: String)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    @Before
    fun setUp() {
        check(OpenCVLoader.initLocal()) { "OpenCV failed to initialize" }
    }

    @Test
    fun benchmarkRecognitionAgainstDbDataset() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("$DIR/tests.txt").bufferedReader().use { it.readText() }
        val spec = Gson().fromJson(json, TestsFile::class.java)

        val recognizer = DieRecognizer()

        var totalDice = 0
        var coveredDice = 0
        var valueCorrect = 0
        // Per-image count of extra (unmatched) detections, bucketed 0 / 1 / 2 / 3+.
        val extraBuckets = IntArray(4)
        val misreads = mutableListOf<String>()

        for (case in spec.tests) {
            val truth = case.dice.filter { it.boundingBox != null }
            val bitmap = assets.open("$DIR/${case.picture_name}").use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                Log.w(LOG_TAG, "${case.picture_name}: could not decode")
                continue
            }
            val detected = runBlocking { recognizer.recognize(bitmap) }

            // Greedy 1:1 matching between labelled dice and detections by IoU, best
            // pairs first. Each labelled die claims at most one detection; whatever
            // detections remain are false positives.
            val claimed = BooleanArray(detected.size)
            val pairs = truth.indices.flatMap { g ->
                detected.indices.map { d ->
                    Triple(g, d, iou(truth[g].boundingBox!!, detected[d].boundingBox))
                }
            }.filter { it.third >= IOU_THRESHOLD }.sortedByDescending { it.third }

            val matchOf = IntArray(truth.size) { -1 }
            for ((g, d, _) in pairs) {
                if (matchOf[g] == -1 && !claimed[d]) {
                    matchOf[g] = d
                    claimed[d] = true
                }
            }

            for (g in truth.indices) {
                totalDice++
                val d = matchOf[g]
                if (d < 0) continue
                coveredDice++
                if (detected[d].value == truth[g].value) {
                    valueCorrect++
                } else {
                    misreads += "${case.picture_name}: ${truth[g].color} die read as " +
                        "${detected[d].value} (expected ${truth[g].value})"
                }
            }

            val extras = claimed.count { !it }
            extraBuckets[extras.coerceAtMost(3)]++
        }

        val images = spec.tests.size
        val recall = pct(coveredDice, totalDice)
        val valueAcc = pct(valueCorrect, totalDice)
        val valueAccCovered = pct(valueCorrect, coveredDice)
        val p1 = pct(extraBuckets[1], images)
        val p2 = pct(extraBuckets[2], images)
        val p3 = pct(extraBuckets[3], images)

        val report = buildString {
            appendLine("==== DB recognition benchmark ($images photos, $totalDice dice) ====")
            appendLine("recall (dice covered) : $coveredDice/$totalDice = ${"%.1f".format(recall)}%  ${verdict(recall >= 100f)} (target 100%)")
            appendLine("value accuracy (all)  : $valueCorrect/$totalDice = ${"%.1f".format(valueAcc)}%  ${verdict(valueAcc >= 80f)} (target ≥80%)")
            appendLine("value accuracy (cov.) : $valueCorrect/$coveredDice = ${"%.1f".format(valueAccCovered)}%  (of covered dice)")
            appendLine("false positives / image:")
            appendLine("   0 extra : ${extraBuckets[0]}/$images = ${"%.1f".format(pct(extraBuckets[0], images))}%")
            appendLine("   1 extra : ${extraBuckets[1]}/$images = ${"%.1f".format(p1)}%  ${verdict(p1 <= 20f)} (budget ≤20%)")
            appendLine("   2 extra : ${extraBuckets[2]}/$images = ${"%.1f".format(p2)}%  ${verdict(p2 <= 5f)} (budget ≤5%)")
            appendLine("  3+ extra : ${extraBuckets[3]}/$images = ${"%.1f".format(p3)}%  ${verdict(p3 <= 0f)} (budget 0%)")
            if (misreads.isNotEmpty()) {
                appendLine("misread dice (${misreads.size}):")
                misreads.forEach { appendLine("   $it") }
            }
        }

        // Log line-by-line: logcat truncates very long single messages.
        report.trimEnd().split("\n").forEach { Log.i(LOG_TAG, it) }
        // Also surface on the test's stdout for `am instrument` runs.
        println(report)
    }

    private fun pct(n: Int, d: Int): Float = if (d == 0) 0f else 100f * n / d

    private fun verdict(ok: Boolean): String = if (ok) "PASS" else "FAIL"

    private fun iou(a: Box, b: BoundingBox): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = b.width * b.height
        return inter / (areaA + areaB - inter)
    }

    private companion object {
        const val DIR = "dbphotos"
        const val LOG_TAG = "DbBenchmark"
        const val IOU_THRESHOLD = 0.5f
    }
}
