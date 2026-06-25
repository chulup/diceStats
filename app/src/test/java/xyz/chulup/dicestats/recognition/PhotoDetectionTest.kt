package xyz.chulup.dicestats.recognition

import com.google.gson.Gson
import xyz.chulup.dicestats.recognition.internal.DiceDetectionPipeline
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream

/**
 * End-to-end detection coverage against the real reference photos, using the
 * ground-truth bounding boxes recorded in `tests.txt`.
 *
 * The photos live under `src/test/resources/photos/` as PPM (`P6`) fixtures —
 * downscaled to the pipeline's working size and stored raw, because Android's
 * unit-test classpath has no JPEG decoder (`javax.imageio`). They are loaded via
 * the classloader and decoded with a tiny pure-JVM PPM reader.
 *
 * A detection is correct when the per-photo die count matches and every labelled
 * die is covered by a detected box with IoU >= [IOU_THRESHOLD].
 */
class PhotoDetectionTest {

    private data class TestsFile(val tests: List<PhotoCase>)
    private data class PhotoCase(val picture_name: String, val dice: List<DieSpec>)
    private data class DieSpec(val boundingBox: Box?, val value: Int, val color: String)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private class Image(val argb: IntArray, val width: Int, val height: Int)

    @Test
    fun detectsDiceInReferencePhotos() {
        val json = resource("photos/tests.txt").bufferedReader().use { it.readText() }
        val spec = Gson().fromJson(json, TestsFile::class.java)

        val failures = mutableListOf<String>()
        for (case in spec.tests) {
            val fixture = case.picture_name.removeSuffix(".jpg") + ".ppm"
            val image = resource("photos/$fixture").use { readPpm(it) }

            val detected = DiceDetectionPipeline.detect(image.argb, image.width, image.height)
            val expected = case.dice.mapNotNull { it.boundingBox }

            if (detected.size != case.dice.size) {
                failures += "${case.picture_name}: expected ${case.dice.size} dice, detected ${detected.size}"
                continue
            }
            val unmatched = expected.count { gt -> detected.none { iou(gt, it) >= IOU_THRESHOLD } }
            if (unmatched > 0) {
                failures += "${case.picture_name}: $unmatched ground-truth box(es) not matched at IoU>=$IOU_THRESHOLD"
            }
        }

        assertTrue("Detection mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun resource(path: String): InputStream =
        javaClass.classLoader!!.getResourceAsStream(path)
            ?: error("Missing test resource: $path")

    /** Minimal binary PPM (P6) reader. */
    private fun readPpm(stream: InputStream): Image {
        val input = DataInputStream(stream.buffered())
        require(readToken(input) == "P6") { "not a P6 PPM" }
        val width = readToken(input).toInt()
        val height = readToken(input).toInt()
        readToken(input) // maxval (assumed 255)

        val rgb = ByteArray(width * height * 3)
        input.readFully(rgb)
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Image(argb, width, height)
    }

    /** Reads one whitespace-delimited ASCII header token. */
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
