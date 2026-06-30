package xyz.chulup.dicestats.feature.stats

import xyz.chulup.dicestats.data.DieType
import kotlin.math.exp
import kotlin.math.ln

/** Fairness conclusion from a chi-square goodness-of-fit test against a uniform d6. */
enum class FairnessVerdict {
    /** Too few rolls to judge (expected count per face below [DieStatistics.MIN_EXPECTED_PER_FACE]). */
    INSUFFICIENT_DATA,
    LOOKS_FAIR,
    POSSIBLY_BIASED,
}

/**
 * Per-die statistics over recorded face values (DESIGN.md "Statistics").
 *
 * Pure Kotlin so it stays JVM-unit-testable. [counts] is indexed by face position
 * within [dieType] (index 0 = the lowest face, e.g. "1" on a d6 or "0" on a d100).
 */
data class DieStatistics(
    val total: Int,
    val counts: List<Int>,
    val mean: Double,
    /** Chi-square statistic for a uniform-distribution null hypothesis (df = sides − 1). */
    val chiSquare: Double,
    /** P-value of [chiSquare]; null when [total] is too small for a verdict. */
    val pValue: Double?,
    val verdict: FairnessVerdict,
    /** The kind of die these statistics describe; drives the face values and expected mean. */
    val dieType: DieType = DieType.DEFAULT,
) {
    /** Largest face count, for scaling a bar chart (at least 1 to avoid divide-by-zero). */
    val maxCount: Int get() = counts.maxOrNull()?.coerceAtLeast(1) ?: 1

    /** Expected uniform mean for a fair die of this [dieType]. */
    val expectedMean: Double get() = dieType.expectedMean

    companion object {
        /** Rule of thumb: chi-square needs an expected count of ~5 per cell to be valid. */
        const val MIN_EXPECTED_PER_FACE = 5.0

        /** Reject fairness below this p-value (standard 5% significance level). */
        const val SIGNIFICANCE = 0.05

        /**
         * Builds statistics from raw recorded values for a [dieType] (d6 by default).
         * Values that aren't a face of the die (e.g. a 7 on a d6) are ignored.
         */
        fun from(values: List<Int>, dieType: DieType = DieType.DEFAULT): DieStatistics {
            val sides = dieType.sides
            val counts = IntArray(sides)
            var sum = 0
            var total = 0
            for (v in values) {
                val index = dieType.faceIndex(v) ?: continue
                counts[index]++
                sum += v
                total++
            }

            val mean = if (total == 0) 0.0 else sum.toDouble() / total
            val expected = total.toDouble() / sides
            val chiSquare = if (total == 0) 0.0 else
                counts.sumOf { c -> val d = c - expected; d * d / expected }

            val enoughData = expected >= MIN_EXPECTED_PER_FACE
            val pValue = if (enoughData) chiSquarePValue(chiSquare, df = sides - 1) else null
            val verdict = when {
                !enoughData -> FairnessVerdict.INSUFFICIENT_DATA
                pValue!! < SIGNIFICANCE -> FairnessVerdict.POSSIBLY_BIASED
                else -> FairnessVerdict.LOOKS_FAIR
            }

            return DieStatistics(total, counts.toList(), mean, chiSquare, pValue, verdict, dieType)
        }

        /**
         * Upper-tail probability P(X > [chiSquare]) for a chi-square distribution with
         * [df] degrees of freedom — i.e. the regularized upper incomplete gamma
         * Q(df/2, chiSquare/2).
         */
        internal fun chiSquarePValue(chiSquare: Double, df: Int): Double {
            if (chiSquare <= 0.0) return 1.0
            return regularizedGammaQ(df / 2.0, chiSquare / 2.0)
        }

        /** Regularized upper incomplete gamma Q(a, x) = 1 - P(a, x). */
        private fun regularizedGammaQ(a: Double, x: Double): Double {
            if (x <= 0.0) return 1.0
            // Series converges fast for x < a+1; continued fraction for the rest.
            return if (x < a + 1.0) 1.0 - gammaPSeries(a, x) else gammaQContinuedFraction(a, x)
        }

        private fun gammaPSeries(a: Double, x: Double): Double {
            var ap = a
            var sum = 1.0 / a
            var del = sum
            repeat(MAX_ITER) {
                ap += 1.0
                del *= x / ap
                sum += del
                if (kotlin.math.abs(del) < kotlin.math.abs(sum) * EPS) return sum * exp(-x + a * ln(x) - lnGamma(a))
            }
            return sum * exp(-x + a * ln(x) - lnGamma(a))
        }

        private fun gammaQContinuedFraction(a: Double, x: Double): Double {
            var b = x + 1.0 - a
            var c = 1.0 / FPMIN
            var d = 1.0 / b
            var h = d
            var i = 1
            while (i <= MAX_ITER) {
                val an = -i * (i - a)
                b += 2.0
                d = an * d + b
                if (kotlin.math.abs(d) < FPMIN) d = FPMIN
                c = b + an / c
                if (kotlin.math.abs(c) < FPMIN) c = FPMIN
                d = 1.0 / d
                val del = d * c
                h *= del
                if (kotlin.math.abs(del - 1.0) < EPS) break
                i++
            }
            return exp(-x + a * ln(x) - lnGamma(a)) * h
        }

        /** Natural log of the gamma function (Lanczos approximation). */
        private fun lnGamma(x: Double): Double {
            val g = doubleArrayOf(
                76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5,
            )
            var y = x
            val tmp = (x + 5.5) - (x + 0.5) * ln(x + 5.5)
            var ser = 1.000000000190015
            for (coef in g) {
                y += 1.0
                ser += coef / y
            }
            return -tmp + ln(2.5066282746310005 * ser / x)
        }

        private const val MAX_ITER = 200
        private const val EPS = 3.0e-12
        private const val FPMIN = 1.0e-300
    }
}
