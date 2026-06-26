package xyz.chulup.dicestats.recognition

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * On-device end-to-end recognition test: runs [DieRecognizer] (saturation
 * detection + OpenCV pip counting) against the reference photos.
 *
 * Recognition favors recall — it may over-propose regions that the user prunes on
 * the confirm screen — so this is a **recall** check: every labelled die in
 * `tests.txt` must be covered by a detection with the correct pip value. Extra
 * (false-positive) proposals are allowed and not asserted against.
 *
 * Pip counting needs OpenCV's native library, so this is an instrumented test
 * rather than a JVM unit test. Fixtures are JPEGs (downscaled to ~1280px, the
 * app's working size, so far-away dice keep enough detail to read) in
 * `androidTest/assets/photos/`. The touching-dice photo (6) is skipped — the
 * detector assumes dice do not touch.
 */
@RunWith(AndroidJUnit4::class)
class PipRecognitionTest {

    private data class TestsFile(val tests: List<PhotoCase>)
    private data class PhotoCase(val picture_name: String, val dice: List<DieSpec>)
    private data class DieSpec(val boundingBox: Box?, val value: Int, val color: String)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val skip = setOf("6.jpg") // dice touch; out of scope

    @Before
    fun setUp() {
        assertTrue("OpenCV failed to initialize", OpenCVLoader.initLocal())
    }

    @Test
    fun recognizesDiceValuesInReferencePhotos() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val json = assets.open("photos/tests.txt").bufferedReader().use { it.readText() }
        val spec = Gson().fromJson(json, TestsFile::class.java)

        val recognizer = DieRecognizer()
        val failures = mutableListOf<String>()

        for (case in spec.tests) {
            if (case.picture_name in skip) continue

            val bitmap = assets.open("photos/${case.picture_name}").use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                failures += "${case.picture_name}: could not decode"
                continue
            }
            val detected = runBlocking { recognizer.recognize(bitmap) }

            // Recall: each labelled die must be covered by a detection reading its
            // value. Extra proposals (false positives) are allowed — the user prunes
            // those on the confirm screen.
            for (gt in case.dice) {
                val box = gt.boundingBox ?: continue
                val overlapping = detected.filter { iou(box, it.boundingBox) >= IOU_THRESHOLD }
                when {
                    overlapping.isEmpty() ->
                        failures += "${case.picture_name}: no detection overlaps the ${gt.color} die"
                    overlapping.none { it.value == gt.value } ->
                        failures += "${case.picture_name}: ${gt.color} die read as " +
                            "${overlapping.map { it.value }}, expected ${gt.value}"
                }
            }
        }

        assertEquals("Recognition mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

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
        const val IOU_THRESHOLD = 0.5f
    }
}
