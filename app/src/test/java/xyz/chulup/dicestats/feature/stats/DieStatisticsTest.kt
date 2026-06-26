package xyz.chulup.dicestats.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieStatisticsTest {

    @Test
    fun emptyInput_hasNoDataVerdict() {
        val s = DieStatistics.from(emptyList())
        assertEquals(0, s.total)
        assertEquals(0.0, s.mean, 0.0)
        assertEquals(FairnessVerdict.INSUFFICIENT_DATA, s.verdict)
        assertEquals(null, s.pValue)
    }

    @Test
    fun countsAndMean_areCorrect() {
        val s = DieStatistics.from(listOf(1, 1, 2, 6))
        assertEquals(listOf(2, 1, 0, 0, 0, 1), s.counts)
        assertEquals(4, s.total)
        assertEquals((1 + 1 + 2 + 6) / 4.0, s.mean, 1e-9)
    }

    @Test
    fun belowMinimumSampleSize_isInsufficientData() {
        // 29 rolls -> expected per face < 5, so no verdict yet.
        val values = (1..29).map { (it % 6) + 1 }
        val s = DieStatistics.from(values)
        assertEquals(FairnessVerdict.INSUFFICIENT_DATA, s.verdict)
        assertEquals(null, s.pValue)
    }

    @Test
    fun perfectlyUniform_looksFair() {
        // 10 of each face: chi-square = 0, p-value = 1.
        val values = (1..6).flatMap { face -> List(10) { face } }
        val s = DieStatistics.from(values)
        assertEquals(60, s.total)
        assertEquals(0.0, s.chiSquare, 1e-9)
        assertEquals(1.0, s.pValue!!, 1e-9)
        assertEquals(FairnessVerdict.LOOKS_FAIR, s.verdict)
    }

    @Test
    fun stronglySkewed_isPossiblyBiased() {
        // Heavily favor face 6.
        val values = List(10) { 1 } + List(10) { 2 } + List(10) { 3 } +
            List(10) { 4 } + List(10) { 5 } + List(60) { 6 }
        val s = DieStatistics.from(values)
        assertEquals(FairnessVerdict.POSSIBLY_BIASED, s.verdict)
        assertTrue("p-value should be tiny, was ${s.pValue}", s.pValue!! < 0.001)
    }

    @Test
    fun chiSquarePValue_matchesKnownCriticalValue() {
        // df=5 critical value at alpha=0.05 is 11.070; its upper-tail p ≈ 0.05.
        val p = DieStatistics.chiSquarePValue(11.070, df = 5)
        assertEquals(0.05, p, 1e-3)
    }

    @Test
    fun outOfRangeValues_areIgnored() {
        val s = DieStatistics.from(listOf(0, 7, 3, -1, 4))
        assertEquals(2, s.total)
        assertEquals(listOf(0, 0, 1, 1, 0, 0), s.counts)
    }
}
