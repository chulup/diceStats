package xyz.chulup.dicestats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults

class SanitizeRollsTest {

    private fun die(id: Long, faces: Int) =
        DieEntity(id = id, name = "d$faces", faces = faces, createdAt = 0)

    private fun roll(id: Long, results: List<Pair<Long?, Int>>) = RollWithResults(
        roll = RollEntity(id = id, photoPath = "p$id", capturedAt = id),
        results = results.mapIndexed { i, (dieId, value) ->
            DieResultEntity(
                id = id * 100 + i,
                rollId = id,
                dieId = dieId,
                value = value,
                confidence = 1f,
                boundingBox = "0,0,1,1",
                wasCorrected = false,
            )
        },
    )

    private fun pairs(rwr: RollWithResults) = rwr.results.map { it.dieId to it.value }

    @Test
    fun dropsImpossibleValuesForAKnownDie() {
        // 7 isn't a face of a d6.
        val out = sanitizeRolls(listOf(roll(1, listOf(1L to 3, 1L to 7))), listOf(die(1, 6)))
        assertEquals(listOf(1L to 3), pairs(out.single()))
    }

    @Test
    fun keepsResultsWhoseDieIsUnknown() {
        // dieId null (the die was deleted) or absent from the dice list -> can't judge, keep.
        val out = sanitizeRolls(
            listOf(roll(1, listOf(null to 99, 2L to 9))),
            listOf(die(1, 6)), // die 2 isn't here
        )
        assertEquals(listOf(null to 99, 2L to 9), pairs(out.single()))
    }

    @Test
    fun d100_keepsTensFaces_dropsTheRest() {
        val out = sanitizeRolls(
            listOf(roll(1, listOf(1L to 0, 1L to 50, 1L to 90, 1L to 5, 1L to 100))),
            listOf(die(1, 100)),
        )
        assertEquals(listOf(1L to 0, 1L to 50, 1L to 90), pairs(out.single()))
    }

    @Test
    fun cleanRoll_isReturnedUnchangedInstance() {
        val rolls = listOf(roll(1, listOf(1L to 1, 1L to 6)))
        val out = sanitizeRolls(rolls, listOf(die(1, 6)))
        assertSame(rolls.single(), out.single()) // no needless copy when nothing is dropped
    }
}
