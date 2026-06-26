package xyz.chulup.dicestats.recognition.internal

import xyz.chulup.dicestats.recognition.DieColorSignature
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes a die's colour fingerprint ([DieColorSignature]) from the pixels inside
 * its bounding box. Framework-free (plain ARGB array) so it is JVM-unit-testable
 * and shared with the on-device path, mirroring [DiceDetectionPipeline].
 *
 * Pips (dark) and specular highlights (blown out) are unreliable, so only the
 * face-surface pixels in a mid brightness band are sampled. Each contributes its
 * saturation-weighted hue direction and its brightness; the means form the
 * signature.
 */
internal object ColorFingerprintPipeline {

    data class Params(
        /** Pixels whose brightest channel is below this are pips/shadows — ignored. */
        val minValue: Int = 50,
        /**
         * Pixels whose *dimmest* channel is at/above this are near-white specular
         * highlights — ignored. (Tests the min channel, so bright saturated colours
         * like pure red are kept; only blown-out whites are dropped.)
         */
        val specularMin: Int = 250,
        /** Need at least this many face pixels to trust the fingerprint. */
        val minSamples: Int = 24,
    )

    /**
     * @param argb row-major ARGB pixels of the whole image.
     * @param box pixel bounds [left,right) x [top,bottom) of the die.
     * @return the fingerprint, or null when too few face pixels were sampled.
     */
    fun signature(
        argb: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        params: Params = Params(),
    ): DieColorSignature? {
        val x0 = left.coerceIn(0, width)
        val x1 = right.coerceIn(0, width)
        val y0 = top.coerceIn(0, height)
        val y1 = bottom.coerceIn(0, height)
        if (x1 <= x0 || y1 <= y0) return null

        var sumA = 0.0
        var sumB = 0.0
        var sumL = 0.0
        var count = 0

        for (y in y0 until y1) {
            val rowBase = y * width
            for (x in x0 until x1) {
                val p = argb[rowBase + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max < params.minValue || min >= params.specularMin) continue
                val delta = max - min
                val satNorm = if (max == 0) 0f else delta.toFloat() / max
                val hueRad = hueRadians(r, g, b, max, delta)
                sumA += (satNorm * cos(hueRad)).toDouble()
                sumB += (satNorm * sin(hueRad)).toDouble()
                sumL += (max / 255f).toDouble()
                count++
            }
        }

        if (count < params.minSamples) return null
        return DieColorSignature(
            chromaA = (sumA / count).toFloat(),
            chromaB = (sumB / count).toFloat(),
            lightness = (sumL / count).toFloat(),
        )
    }

    /** HSV hue in radians (0 when achromatic). */
    private fun hueRadians(r: Int, g: Int, b: Int, max: Int, delta: Int): Float {
        if (delta == 0) return 0f
        val d = delta.toFloat()
        val hue60 = when (max) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return Math.toRadians((hue60 * 60f).toDouble()).toFloat()
    }
}
