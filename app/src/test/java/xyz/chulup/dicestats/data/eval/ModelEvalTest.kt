package xyz.chulup.dicestats.data.eval

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DetectedDie

class ModelEvalTest {

    private fun box(x: Float, y: Float, s: Float = 0.1f) = BoundingBox(x, y, x + s, y + s)
    private fun det(b: BoundingBox, v: Int?) = DetectedDie(value = v, boundingBox = b)
    private fun conf(b: BoundingBox, v: Int) = ConfirmedBox(b, v, v, DieOrigin.DETECTED, blurry = false)

    @Test
    fun countsFoundCorrectMissedAndExtra() {
        val confirmed = listOf(conf(box(0.1f, 0.1f), 3), conf(box(0.5f, 0.5f), 6), conf(box(0.8f, 0.1f), 1))
        val predicted = listOf(
            det(box(0.11f, 0.1f), 3), // matches die 1, right value
            det(box(0.5f, 0.52f), 5), // matches die 2, wrong value
            det(box(0.3f, 0.8f), 2), // nothing there
        )
        assertEquals(RunScore(found = 2, valueCorrect = 1, missed = 1, extra = 1), scoreRun(predicted, confirmed))
    }

    @Test
    fun matchesEachConfirmedDieOnce() {
        val confirmed = listOf(conf(box(0.1f, 0.1f), 4))
        val predicted = listOf(det(box(0.1f, 0.1f), 4), det(box(0.105f, 0.1f), 4))
        assertEquals(RunScore(found = 1, valueCorrect = 1, missed = 0, extra = 1), scoreRun(predicted, confirmed))
    }

    @Test
    fun lowOverlapIsNotAMatch() {
        val score = scoreRun(listOf(det(box(0.1f, 0.1f), 2)), listOf(conf(box(0.16f, 0.1f), 2)))
        assertEquals(RunScore(found = 0, valueCorrect = 0, missed = 1, extra = 1), score)
    }
}
