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
import xyz.chulup.dicestats.feature.detection.sampleSizeFor
import java.io.File

/**
 * On-device benchmark over the **live** roll data already on the device: the app's
 * own SQLite database (`dicestats.db`) and the roll photos it points at
 * (the JPEGs under `<externalFilesDir>/rolls`). Every stored `die_results` row is ground
 * truth — it is either what the recognizer read and the user accepted, or the
 * value the user corrected it to on the confirm screen. Nothing is baked into the
 * APK; the numbers reflect whatever rolls this device currently holds.
 *
 * It replays the production pipeline exactly — [decodeOriented] (EXIF-rotated,
 * downscaled to 1280px, as `DetectionViewModel` does) then [DieRecognizer] — so
 * fresh detections land in the same oriented, normalized frame as the stored
 * boxes. It prints a **scorecard** for the three targets we hold every recognition
 * algorithm to:
 *
 *  - **Detection recall** — every stored die is covered by a detection
 *    (target: 100%).
 *  - **Value accuracy** — the covering detection reads the stored pip value
 *    (target: ≥80% of dice).
 *  - **False positives** — extra (unmatched) detections per image, within budget:
 *    ≤20% of images may have 1 extra, ≤5% may have 2 extra, none 3+.
 *
 * **Report-only**: it always passes and logs the scorecard (tag [LOG_TAG]) so the
 * numbers can be tracked as the pipeline evolves. Box convention and matching
 * (greedy IoU ≥ 0.5) match `PipRecognitionTest` / `tests.txt`.
 *
 * Precondition: run against a device/emulator whose installed app has captured
 * rolls. An empty database logs a note and passes.
 */
@RunWith(AndroidJUnit4::class)
class DbBenchmarkTest {

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        System.setProperty("org.bytedeco.javacpp.cachedir", ctx.cacheDir.absolutePath)
        Loader.load(opencv_core::class.java)
    }

    @Test
    fun benchmarkRecognitionAgainstOnDeviceRolls() {
        // targetContext = the app under test, so the DB name and the photoPaths in
        // it resolve to the same files the app reads.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(ctx, DiceDatabase::class.java, DB_NAME)
            .addMigrations(
                DiceDatabase.MIGRATION_1_2,
                DiceDatabase.MIGRATION_2_3,
                DiceDatabase.MIGRATION_3_4,
            )
            .build()

        try {
            val rolls = runBlocking { db.rollDao().observeRollsWithResults().first() }
            // Only judge pip dice: results on an unknown die (deleted/absent) are kept
            // since we cannot tell their kind; results on a registered non-pip die are
            // skipped because the pip recognizer does not read them.
            val pipDieIds = runBlocking { db.dieDao().observeAll().first() }
                .filter { it.kind == PIP_KIND }.map { it.id }.toSet()
            val dieNames = runBlocking { db.dieDao().observeAll().first() }
                .associate { it.id to it.name }

            val recognizer = DieRecognizer()

            var images = 0
            var missingPhotos = 0
            var totalDice = 0
            var coveredDice = 0
            var valueCorrect = 0
            // Per-image count of extra (unmatched) detections, bucketed 0 / 1 / 2 / 3+.
            val extraBuckets = IntArray(4)
            val misreads = mutableListOf<String>()

            for (roll in rolls) {
                val truth = roll.results
                    .filter { it.dieId == null || it.dieId in pipDieIds }
                    .mapNotNull { r -> parseBox(r.boundingBox)?.let { it to r } }
                if (truth.isEmpty()) continue

                val photoPath = roll.roll.photoPath ?: continue
                val bitmap = decodeOriented(photoPath)
                if (bitmap == null) {
                    missingPhotos++
                    Log.w(LOG_TAG, "roll ${roll.roll.id}: could not decode $photoPath")
                    continue
                }
                images++
                val detected = try {
                    runBlocking { recognizer.recognize(bitmap) }
                } finally {
                    bitmap.recycle()
                }

                // Greedy 1:1 matching between stored dice and detections by IoU, best
                // pairs first. Each stored die claims at most one detection; whatever
                // detections remain are false positives.
                val claimed = BooleanArray(detected.size)
                val pairs = truth.indices.flatMap { g ->
                    detected.indices.map { d ->
                        Triple(g, d, iou(truth[g].first, detected[d].boundingBox))
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
                    val expected = truth[g].second.value
                    if (detected[d].value == expected) {
                        valueCorrect++
                    } else {
                        val name = truth[g].second.dieId?.let { dieNames[it] } ?: "unknown die"
                        misreads += "roll ${roll.roll.id}: $name read as " +
                            "${detected[d].value} (expected $expected)"
                    }
                }

                extraBuckets[claimed.count { !it }.coerceAtMost(3)]++
            }

            val report = buildReport(
                images, missingPhotos, totalDice, coveredDice, valueCorrect,
                extraBuckets, misreads,
            )
            // Log line-by-line: logcat truncates very long single messages.
            report.trimEnd().split("\n").forEach { Log.i(LOG_TAG, it) }
            // Also surface on the test's stdout for `am instrument` runs.
            println(report)
        } finally {
            db.close()
        }
    }

    private fun buildReport(
        images: Int,
        missingPhotos: Int,
        totalDice: Int,
        coveredDice: Int,
        valueCorrect: Int,
        extraBuckets: IntArray,
        misreads: List<String>,
    ): String {
        if (totalDice == 0) {
            return "==== On-device recognition benchmark ====\n" +
                "No pip-die results found in $DB_NAME" +
                (if (missingPhotos > 0) " ($missingPhotos rolls had unreadable photos)" else "") +
                ". Capture some rolls first, then re-run."
        }
        val recall = pct(coveredDice, totalDice)
        val valueAcc = pct(valueCorrect, totalDice)
        val valueAccCovered = pct(valueCorrect, coveredDice)
        val p1 = pct(extraBuckets[1], images)
        val p2 = pct(extraBuckets[2], images)
        val p3 = pct(extraBuckets[3], images)
        return buildString {
            appendLine("==== On-device recognition benchmark ($images photos, $totalDice dice) ====")
            if (missingPhotos > 0) appendLine("(skipped $missingPhotos rolls with unreadable photos)")
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
    }

    /**
     * Decodes [path] the way `DetectionViewModel` does before recognition: downscaled
     * to a 1280px long edge and rotated to its EXIF display orientation, so detections
     * share the oriented, normalized frame the stored boxes were saved in.
     */
    private fun decodeOriented(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val rotation = exifRotationDegrees(path)
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    private fun exifRotationDegrees(path: String): Int =
        when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    /** Parses a stored `"left,top,right,bottom"` box, or null if malformed. */
    private fun parseBox(s: String): BoundingBox? {
        val p = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (p.size == 4) BoundingBox(p[0], p[1], p[2], p[3]) else null
    }

    private fun pct(n: Int, d: Int): Float = if (d == 0) 0f else 100f * n / d

    private fun verdict(ok: Boolean): String = if (ok) "PASS" else "FAIL"

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
        const val DB_NAME = "dicestats.db"
        const val PIP_KIND = "PIPPED"
        const val LOG_TAG = "DbBenchmark"
        const val IOU_THRESHOLD = 0.5f
        const val TARGET_MAX_EDGE = 1280
    }
}
