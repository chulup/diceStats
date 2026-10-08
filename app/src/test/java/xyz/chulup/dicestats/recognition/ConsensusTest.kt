package xyz.chulup.dicestats.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsensusTest {

    private fun die(x: Float, y: Float, v: Int?, s: Float = 0.1f) = DetectedDie(value = v, boundingBox = BoundingBox(x, y, x + s, y + s))

    @Test
    fun twoOfThreeAgreeOnBoxAndValue() {
        val out = Consensus.vote(listOf(listOf(die(0.1f, 0.1f, 3)), listOf(die(0.105f, 0.1f, 3)), listOf(die(0.1f, 0.102f, 5))))
        assertEquals(1, out.size)
        assertEquals(3, out[0].value)
        assertEquals(3, out[0].votes)
        assertEquals(2, out[0].valueVotes)
    }

    @Test
    fun disagreeingValuesLeaveTheDieUnread() {
        val out = Consensus.vote(listOf(listOf(die(0.1f, 0.1f, 1)), listOf(die(0.1f, 0.1f, 2)), listOf(die(0.1f, 0.1f, 3))))
        assertEquals(1, out.size)
        assertNull(out[0].value)
        assertEquals(0, out[0].valueVotes)
    }

    @Test
    fun aDieFoundByOnePipelineIsDropped() {
        val out = Consensus.vote(listOf(listOf(die(0.1f, 0.1f, 4), die(0.6f, 0.6f, 2)), listOf(die(0.1f, 0.1f, 4)), emptyList()))
        assertEquals(1, out.size)
        assertEquals(4, out[0].value)
    }

    @Test
    fun neighbouringDiceStaySeparate() {
        val a = listOf(die(0.1f, 0.1f, 1), die(0.25f, 0.1f, 6))
        val out = Consensus.vote(listOf(a, a, a))
        assertEquals(listOf(1, 6), out.map { it.value })
        assertEquals(0.1f, out[0].box.left, 1e-6f)
    }
}
