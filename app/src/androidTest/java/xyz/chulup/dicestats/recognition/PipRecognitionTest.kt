package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
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
import java.io.DataInputStream
import java.io.InputStream

/**
 * On-device end-to-end recognition test: runs [DieRecognizer] (saturation
 * detection + OpenCV pip counting) against the reference photos and checks that
 * each die's location and pip value match the ground truth in `tests.txt`.
 *
 * Pip counting needs OpenCV's native library, so this is an instrumented test
 * rather than a JVM unit test. Fixtures are PPM (downscaled, raw) in
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

            val fixture = case.picture_name.removeSuffix(".jpg") + ".ppm"
            val bitmap = assets.open("photos/$fixture").use { readPpm(it) }
            val detected = runBlocking { recognizer.recognize(bitmap) }

            if (detected.size != case.dice.size) {
                failures += "${case.picture_name}: expected ${case.dice.size} dice, detected ${detected.size}"
                continue
            }
            for (gt in case.dice) {
                val box = gt.boundingBox ?: continue
                val match = detected.firstOrNull { iou(box, it.boundingBox) >= IOU_THRESHOLD }
                when {
                    match == null ->
                        failures += "${case.picture_name}: no detection overlaps the ${gt.color} die"
                    match.value != gt.value ->
                        failures += "${case.picture_name}: ${gt.color} die value ${match.value}, expected ${gt.value}"
                }
            }
        }

        assertEquals("Recognition mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    private fun readPpm(stream: InputStream): Bitmap {
        val input = DataInputStream(stream.buffered())
        require(readToken(input) == "P6") { "not a P6 PPM" }
        val width = readToken(input).toInt()
        val height = readToken(input).toInt()
        readToken(input) // maxval

        val rgb = ByteArray(width * height * 3)
        input.readFully(rgb)
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun readToken(input: DataInputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        while (c != -1 && Character.isWhitespace(c)) c = input.read()
        while (c != -1 && !Character.isWhitespace(c)) {
            sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
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
