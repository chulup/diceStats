package xyz.chulup.dicestats.recognition.internal

import xyz.chulup.dicestats.recognition.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast, deterministic coverage of the detection pipeline on synthetic images
 * (no file I/O). Realistic-photo coverage lives in `PhotoDetectionTest`.
 */
class DiceDetectionPipelineTest {

    private val gray = argb(150, 150, 150) // desaturated background
    private val red = argb(220, 30, 30) // saturated, bright "die"

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Paints a filled square of [color] onto a [bg] canvas. */
    private fun canvas(
        width: Int,
        height: Int,
        bg: Int,
        squares: List<IntArray>, // [left, top, side]
        color: Int,
    ): IntArray {
        val px = IntArray(width * height) { bg }
        for (sq in squares) {
            val (left, top, side) = sq
            for (y in top until top + side) {
                for (x in left until left + side) {
                    px[y * width + x] = color
                }
            }
        }
        return px
    }

    private fun centerX(b: BoundingBox) = (b.left + b.right) / 2f

    @Test
    fun detectsSingleSaturatedSquare() {
        val w = 200
        val h = 200
        val px = canvas(w, h, gray, listOf(intArrayOf(60, 60, 60)), red)

        val boxes = DiceDetectionPipeline.detect(px, w, h)

        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertEquals(0.30f, b.left, 0.06f)
        assertEquals(0.30f, b.top, 0.06f)
        assertEquals(0.60f, b.right, 0.06f)
        assertEquals(0.60f, b.bottom, 0.06f)
    }

    @Test
    fun ignoresDesaturatedBackgroundOnly() {
        val w = 200
        val h = 200
        val px = IntArray(w * h) { gray }

        assertTrue(DiceDetectionPipeline.detect(px, w, h).isEmpty())
    }

    @Test
    fun detectsTwoSeparateSquares() {
        val w = 240
        val h = 160
        val px = canvas(
            w, h, gray,
            squares = listOf(intArrayOf(30, 50, 50), intArrayOf(160, 50, 50)),
            color = red,
        )

        val boxes = DiceDetectionPipeline.detect(px, w, h).sortedBy { centerX(it) }

        assertEquals(2, boxes.size)
        assertTrue(centerX(boxes[0]) < 0.5f)
        assertTrue(centerX(boxes[1]) > 0.5f)
    }

    @Test
    fun splitsTwoTouchingSquares() {
        val w = 240
        val h = 200
        // Two squares overlapping at a corner (as real dice touch) — distinct distance
        // transform peaks, so the pipeline must split them into two dice.
        val px = canvas(
            w, h, gray,
            squares = listOf(intArrayOf(50, 40, 60), intArrayOf(95, 85, 60)),
            color = red,
        )

        val boxes = DiceDetectionPipeline.detect(px, w, h)

        assertEquals(2, boxes.size)
    }

    @Test
    fun otsuPassDetectsUnsaturatedDieOnPlainBackground() {
        val w = 200
        val h = 200
        // A desaturated (metallic/white-ish) die: the saturation pass is blind to it, but it
        // stands out in luminance against a plain background, so the Otsu pass should find it.
        val lightBg = argb(200, 200, 200)
        val darkDie = argb(60, 60, 60)
        val px = canvas(w, h, lightBg, listOf(intArrayOf(70, 70, 60)), darkDie)

        // Saturation-only sees nothing (zero-saturation die on a zero-saturation background).
        val satOnly = DiceDetectionPipeline.Params(otsuOnPlainBackground = false)
        assertTrue(DiceDetectionPipeline.detect(px, w, h, satOnly).isEmpty())

        // With the Otsu pass enabled (default), the die is detected.
        val boxes = DiceDetectionPipeline.detect(px, w, h)
        assertEquals(1, boxes.size)
    }

    @Test
    fun otsuPassStaysOffOnTexturedBackground() {
        val w = 200
        val h = 200
        // A fine per-pixel checkerboard makes the scene "cluttered" (edge density ~1), so the
        // Otsu gate must stay closed — otherwise Otsu would flood textured photos with blobs.
        val dark = argb(80, 80, 80)
        val light = argb(180, 180, 180)
        val px = IntArray(w * h) { if (((it % w) + (it / w)) % 2 == 0) dark else light }
        // Lay a desaturated die on top; only the (gated-off) Otsu pass could ever see it.
        for (y in 70 until 130) for (x in 70 until 130) px[y * w + x] = argb(120, 120, 120)

        // Saturation is blind to it, and the texture keeps Otsu gated off → nothing detected.
        assertTrue(DiceDetectionPipeline.detect(px, w, h).isEmpty())
    }

    @Test
    fun rejectsThinNonSquareStripe() {
        val w = 200
        val h = 200
        // A long thin saturated bar (aspect ~10) — not a die.
        val px = canvas(w, h, gray, listOf(), red).also {
            for (y in 96 until 104) for (x in 20 until 180) it[y * w + x] = red
        }

        assertTrue(DiceDetectionPipeline.detect(px, w, h).isEmpty())
    }
}
