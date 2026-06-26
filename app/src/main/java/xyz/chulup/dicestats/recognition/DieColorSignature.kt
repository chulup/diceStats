package xyz.chulup.dicestats.recognition

import kotlin.math.sqrt

/**
 * A compact colour fingerprint of a die's top face, used to guess which registered
 * physical die it is (DESIGN.md "identity matching").
 *
 * The face colour is represented in a chroma/lightness space derived from HSV:
 * [chromaA]/[chromaB] are the saturation-weighted hue direction (so colour matters
 * in proportion to how colourful the face is) and [lightness] is its brightness.
 * This separates hue from brightness, so colored dice are told apart by hue while
 * white/grey dice (near-zero chroma) are told apart by lightness.
 *
 * Pure Kotlin so it stays JVM-unit-testable; computed by [internal.ColorFingerprintPipeline].
 */
data class DieColorSignature(
    val chromaA: Float,
    val chromaB: Float,
    val lightness: Float,
) {
    /** Serialized form for the `dice` table. */
    fun encode(): String = "$chromaA,$chromaB,$lightness"

    companion object {
        /**
         * Brightness is less reliable than hue (it shifts with lighting), so it
         * contributes less to the match distance.
         */
        const val LIGHTNESS_WEIGHT = 0.5f

        /**
         * Above this distance there is no match. A starting hypothesis to calibrate
         * against real labelled rolls, not a fixed rule (DESIGN.md).
         */
        const val MATCH_MAX_DISTANCE = 0.30f

        fun decode(s: String): DieColorSignature? {
            val parts = s.split(",")
            if (parts.size != 3) return null
            val a = parts[0].toFloatOrNull() ?: return null
            val b = parts[1].toFloatOrNull() ?: return null
            val l = parts[2].toFloatOrNull() ?: return null
            return DieColorSignature(a, b, l)
        }

        /** Distance in chroma/lightness space; smaller means a closer colour match. */
        fun distance(x: DieColorSignature, y: DieColorSignature): Float {
            val da = x.chromaA - y.chromaA
            val db = x.chromaB - y.chromaB
            val dl = x.lightness - y.lightness
            return sqrt(da * da + db * db + LIGHTNESS_WEIGHT * dl * dl)
        }

        /**
         * Running mean of two signatures, weighted by how many samples each is built
         * from — used to refine a die's stored fingerprint as more rolls are logged.
         */
        fun merge(
            old: DieColorSignature,
            oldSamples: Int,
            new: DieColorSignature,
            newSamples: Int = 1,
        ): DieColorSignature {
            val total = (oldSamples + newSamples).coerceAtLeast(1)
            fun avg(o: Float, n: Float) = (o * oldSamples + n * newSamples) / total
            return DieColorSignature(
                chromaA = avg(old.chromaA, new.chromaA),
                chromaB = avg(old.chromaB, new.chromaB),
                lightness = avg(old.lightness, new.lightness),
            )
        }
    }
}
