package xyz.chulup.dicestats.recognition

import com.google.gson.Gson
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import xyz.chulup.dicestats.recognition.internal.DiceDetectionPipeline
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.io.InputStream

/**
 * Detection + recognition benchmark over the whole photo corpus. Not an assertion — it runs
 * the full `DiceDetectionPipeline.detect` -> `PipCounter.count` pipeline on every fixtured,
 * non-disabled photo in `photos/tests.txt` and prints aggregate statistics, so any algorithm
 * change can be evaluated A/B against the numbers in RESEARCH.md.
 *
 * It currently reports two configurations side by side — the white/bright pass ON vs OFF —
 * because that pass is gated behind a flag; to evaluate a *different* change, point both
 * configs at the relevant [DiceDetectionPipeline.Params] (e.g. a new param on vs off, or a
 * tweaked default vs the current one) in [configs].
 *
 * Metrics:
 *  - **found-something recall** — photos with >=1 ground-truth die where >=1 box was proposed.
 *  - **boxed-GT recall** — ground-truth dice that carry a box (the 1-12 set), covered at IoU>=0.5.
 *  - **value-match** — read pip values matched against ground-truth values as a multiset.
 *  - **proposed / readable** — total proposed boxes, and how many pip-read to a number (vs `?`).
 */
class DetectionStatsReport {

    private data class TestsFile(val tests: List<PhotoCase>)
    private data class PhotoCase(
        val picture_name: String,
        val disabled: Boolean = false,
        val dice: List<DieSpec> = emptyList(),
    )
    private data class DieSpec(val boundingBox: Box?, val value: Int)
    private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)
    private class Image(val argb: IntArray, val width: Int, val height: Int)

    /** A named pipeline configuration to benchmark. Add/replace entries to evaluate a change. */
    private val configs = listOf(
        "white pass ON (current)" to DiceDetectionPipeline.Params(whitePass = true),
        "white pass OFF (pre-fix)" to DiceDetectionPipeline.Params(whitePass = false),
    )

    private val pipCounter = PipCounter()

    private class Agg {
        var photos = 0
        var gtDice = 0
        var boxes = 0
        var readable = 0
        var valueMatches = 0
        var foundSomething = 0 // photos with >=1 gt die where >=1 box proposed
        var photosWithGt = 0
        var boxedGt = 0         // gt dice that carry a ground-truth box
        var boxedGtCovered = 0  // ...covered by a proposal at IoU>=0.5
    }

    @Test
    fun report() {
        val spec = Gson().fromJson(testsFile().readText(), TestsFile::class.java)
        val aggs = configs.map { Agg() }
        println("CSV photo,gt," + configs.joinToString(",") { "${it.first} boxes/read/valmatch" })
        for (case in spec.tests) {
            if (case.disabled) continue
            val fixture = case.picture_name.removeSuffix(".jpg") + ".ppm"
            val stream = javaClass.classLoader!!.getResourceAsStream("photos/$fixture") ?: continue
            val image = stream.use { readPpm(it) }
            val rgba = rgbaMat(image)
            val gtValues = case.dice.map { it.value }
            val gtBoxes = case.dice.mapNotNull { it.boundingBox }

            val cells = configs.mapIndexed { i, (_, params) ->
                eval(image, rgba, gtValues, gtBoxes, params, aggs[i])
            }
            rgba.release()
            println("CSV ${case.picture_name},${case.dice.size},${cells.joinToString(",")}")
        }
        configs.forEachIndexed { i, (name, _) -> printAgg(name, aggs[i]) }
    }

    private fun eval(
        image: Image, rgba: Mat, gtValues: List<Int>, gtBoxes: List<Box>,
        params: DiceDetectionPipeline.Params, agg: Agg,
    ): String {
        val boxes = DiceDetectionPipeline.detect(image.argb, image.width, image.height, params)
        val values = boxes.map { pipCounter.count(rgba, it) }
        val readable = values.count { it != null }
        // Multiset match of read values against ground-truth values.
        val remaining = gtValues.toMutableList()
        var matches = 0
        for (v in values) if (v != null && remaining.remove(v)) matches++

        agg.photos++
        agg.gtDice += gtValues.size
        agg.boxes += boxes.size
        agg.readable += readable
        agg.valueMatches += matches
        if (gtValues.isNotEmpty()) {
            agg.photosWithGt++
            if (boxes.isNotEmpty()) agg.foundSomething++
        }
        for (gt in gtBoxes) {
            agg.boxedGt++
            if (boxes.any { iou(gt, it) >= 0.5f }) agg.boxedGtCovered++
        }
        return "${boxes.size},$readable,$matches"
    }

    private fun printAgg(title: String, a: Agg) {
        println("=== $title ===")
        println("  photos=${a.photos} gtDice=${a.gtDice}")
        println("  proposed boxes=${a.boxes}  readable(non-?)=${a.readable}")
        println("  value matches=${a.valueMatches}/${a.gtDice} (${pct(a.valueMatches, a.gtDice)})")
        println("  found-something recall=${a.foundSomething}/${a.photosWithGt} (${pct(a.foundSomething, a.photosWithGt)})")
        println("  boxed-GT IoU>=0.5 recall=${a.boxedGtCovered}/${a.boxedGt} (${pct(a.boxedGtCovered, a.boxedGt)})")
    }

    private fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.0f%%".format(100.0 * n / d)

    private fun rgbaMat(image: Image): Mat {
        val bytes = ByteArray(image.argb.size * 4)
        for (i in image.argb.indices) {
            val p = image.argb[i]
            bytes[i * 4] = ((p shr 16) and 0xFF).toByte()
            bytes[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = (p and 0xFF).toByte()
            bytes[i * 4 + 3] = 0xFF.toByte()
        }
        val mat = Mat(image.height, image.width, opencv_core.CV_8UC4)
        mat.data().put(bytes, 0, bytes.size)
        return mat
    }

    private fun testsFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            dir?.let { d ->
                val f = File(d, "photos/tests.txt")
                if (f.exists()) return f
                dir = d.parentFile
            }
        }
        error("Could not locate photos/tests.txt")
    }

    private fun readPpm(stream: InputStream): Image {
        val input = DataInputStream(stream.buffered())
        require(readToken(input) == "P6") { "not a P6 PPM" }
        val width = readToken(input).toInt()
        val height = readToken(input).toInt()
        readToken(input)
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

    private fun readToken(input: DataInputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        while (c != -1 && Character.isWhitespace(c)) c = input.read()
        while (c != -1 && !Character.isWhitespace(c)) { sb.append(c.toChar()); c = input.read() }
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
        return inter / (areaA + b.width * b.height - inter)
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            Loader.load(opencv_core::class.java)
        }
    }
}
