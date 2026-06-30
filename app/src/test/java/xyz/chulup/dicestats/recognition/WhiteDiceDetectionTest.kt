package xyz.chulup.dicestats.recognition

import xyz.chulup.dicestats.recognition.internal.DiceDetectionPipeline
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream

/**
 * Locks the white-dice detection fix. Photos 201–207 are white d6 on a light-wood floor
 * (the captures the device reported as unrecognized): the dice are unsaturated, so the
 * saturation pass is blind to them, and the wood grain keeps the Otsu pass gated off — so
 * before the white/bright pass they were detected as **nothing at all**.
 *
 * For each fixture this asserts the white pass turns that zero into real detections. The
 * boxes were visually confirmed to land on the dice (see the curation contact sheet); the
 * per-die boxes are left null in `tests.txt` (analysis-only, like the other hard cases),
 * so this count-based check is what guards against a regression.
 */
class WhiteDiceDetectionTest {

    private class Image(val argb: IntArray, val width: Int, val height: Int)

    @Test
    fun whitePassDetectsWhiteDiceThatTheOtherPassesMiss() {
        val failures = mutableListOf<String>()
        for (n in 201..207) {
            val stream = javaClass.classLoader!!.getResourceAsStream("photos/$n.ppm")
            requireNotNull(stream) { "missing fixture photos/$n.ppm" }
            val img = stream.use { readPpm(it) }

            val withoutWhite = DiceDetectionPipeline.detect(
                img.argb, img.width, img.height,
                DiceDetectionPipeline.Params(whitePass = false),
            )
            val withWhite = DiceDetectionPipeline.detect(
                img.argb, img.width, img.height,
                DiceDetectionPipeline.Params(whitePass = true),
            )
            if (withWhite.isEmpty()) {
                failures += "$n.ppm: white pass found no dice (without-white=${withoutWhite.size})"
            }
        }
        assertTrue(
            "White dice went undetected:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun readPpm(stream: InputStream): Image {
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
        return Image(argb, width, height)
    }

    private fun readToken(input: DataInputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        while (c != -1 && Character.isWhitespace(c)) c = input.read()
        while (c != -1 && !Character.isWhitespace(c)) { sb.append(c.toChar()); c = input.read() }
        return sb.toString()
    }
}
