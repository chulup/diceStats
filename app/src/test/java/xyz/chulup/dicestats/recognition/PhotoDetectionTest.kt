package xyz.chulup.dicestats.recognition

import com.google.gson.Gson
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import xyz.chulup.dicestats.recognition.internal.DiceDetectionPipeline
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * End-to-end detection + pip-reading coverage against the real reference photos.
 *
 * Ground truth is the canonical `photos/tests.txt` (read straight from the repo, so
 * there's a single source of truth); entries flagged `"disabled": true` — touching
 * dice, polyhedral d8/d10/d20/d100 — are skipped. The photos themselves live under
 * `src/test/resources/photos/` as PPM (`P6`) fixtures (downscaled to the pipeline's
 * working size, stored raw because the unit-test classpath has no JPEG decoder).
 *
 * Two things are checked/logged per photo:
 *  - **Recall (asserted):** every labelled die with a box must be covered by a
 *    proposed box at IoU >= [IOU_THRESHOLD]. The pipeline over-proposes on purpose;
 *    false positives are pruned later (by pip counting / the user), so they aren't
 *    asserted here.
 *  - **Otsu A/B + pip values (logged):** detection runs with the Otsu plain-background
 *    pass off and on, and each proposed box is pip-counted with the real [PipCounter]
 *    (bytedeco/JavaCPP OpenCV, whose natives load on the dev machine). The per-photo
 *    line — file name, boxes found without vs with Otsu, each box's value or `?` — is
 *    printed for analysis.
 */
class PhotoDetectionTest {

    private data class TestsFile(val tests: List<PhotoCase>)
    private data class PhotoCase(
        val picture_name: String,
        val disabled: Boolean = false,
        val dice: List<DieSpec> = emptyList(),
    )
    private data class DieSpec(val boundingBox: Box?, val value: Int, val color: String?)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private class Image(val argb: IntArray, val width: Int, val height: Int)

    private val pipCounter = PipCounter()

    @Test
    fun detectsAndReadsDiceInReferencePhotos() {
        val spec = Gson().fromJson(testsFile().readText(), TestsFile::class.java)

        val failures = mutableListOf<String>()
        println("=== Otsu A/B detection sweep (without-otsu | with-otsu, value or ?) ===")
        for (case in spec.tests) {
            if (case.disabled) continue

            val fixture = case.picture_name.removeSuffix(".jpg") + ".ppm"
            val stream = javaClass.classLoader!!.getResourceAsStream("photos/$fixture")
            if (stream == null) {
                // d6 photos whose PPM fixture hasn't been generated yet (e.g. 101–110).
                println("${case.picture_name}: skipped (no fixture)")
                continue
            }
            val image = stream.use { readPpm(it) }
            val rgba = rgbaMat(image)

            val withoutOtsu = detect(image, otsu = false)
            val withOtsu = detect(image, otsu = true)
            println(
                "${case.picture_name}: without-otsu=${values(rgba, withoutOtsu)} " +
                    "with-otsu=${values(rgba, withOtsu)}",
            )
            rgba.release()

            val expected = case.dice.mapNotNull { it.boundingBox }
            val unmatched = expected.count { gt -> withOtsu.none { iou(gt, it) >= IOU_THRESHOLD } }
            if (unmatched > 0) {
                failures += "${case.picture_name}: $unmatched labelled die(s) not proposed at IoU>=$IOU_THRESHOLD"
            }
        }

        assertTrue("Detection mismatches:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun detect(image: Image, otsu: Boolean): List<BoundingBox> =
        DiceDetectionPipeline.detect(
            image.argb, image.width, image.height,
            DiceDetectionPipeline.Params(otsuOnPlainBackground = otsu),
        )

    /** Each proposed box's pip value (or `?` when unreadable), e.g. `[3, ?, 5]`. */
    private fun values(rgba: Mat, boxes: List<BoundingBox>): String =
        boxes.joinToString(prefix = "[", postfix = "]") { (pipCounter.count(rgba, it)?.toString() ?: "?") }

    /** Builds the 4-channel R,G,B,A Mat that [PipCounter] expects. */
    private fun rgbaMat(image: Image): Mat {
        val bytes = ByteArray(image.argb.size * 4)
        for (i in image.argb.indices) {
            val p = image.argb[i]
            bytes[i * 4] = ((p shr 16) and 0xFF).toByte() // R
            bytes[i * 4 + 1] = ((p shr 8) and 0xFF).toByte() // G
            bytes[i * 4 + 2] = (p and 0xFF).toByte() // B
            bytes[i * 4 + 3] = 0xFF.toByte() // A
        }
        val mat = Mat(image.height, image.width, opencv_core.CV_8UC4)
        mat.data().put(bytes, 0, bytes.size)
        return mat
    }

    /** Locates the canonical ground-truth file by walking up from the test working dir. */
    private fun testsFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            dir?.let { d ->
                val f = File(d, "photos/tests.txt")
                if (f.exists()) return f
                dir = d.parentFile
            }
        }
        error("Could not locate photos/tests.txt from ${System.getProperty("user.dir")}")
    }

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

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            // bytedeco/JavaCPP desktop natives — the same OpenCV the app uses on-device.
            Loader.load(opencv_core::class.java)
        }
    }
}
