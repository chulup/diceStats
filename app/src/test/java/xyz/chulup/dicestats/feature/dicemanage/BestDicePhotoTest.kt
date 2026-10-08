package xyz.chulup.dicestats.feature.dicemanage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.chulup.dicestats.data.db.DieResultEntity
import xyz.chulup.dicestats.data.db.RollEntity
import xyz.chulup.dicestats.data.db.RollWithResults

class BestDicePhotoTest {

    private fun result(dieId: Long?, box: String, conf: Float = 0.9f) =
        DieResultEntity(rollId = 0, dieId = dieId, value = 3, confidence = conf, boundingBox = box, wasCorrected = false)

    private fun roll(id: Long, path: String?, at: Long, vararg r: DieResultEntity) =
        RollWithResults(RollEntity(id = id, photoPath = path, capturedAt = at), r.toList())

    @Test
    fun noPhotos_returnsNull() {
        assertNull(pickBestPicture(1, 1, listOf(roll(1, null, 1, result(1, "")))))
        assertNull(pickBestPicture(1, 1, listOf(roll(1, "a.jpg", 1, result(2, "0.1,0.1,0.2,0.2")))))
    }

    @Test
    fun singleDie_prefersHigherConfidence_thenNewer() {
        val rolls = listOf(
            roll(1, "low.jpg", 1, result(1, "0.2,0.2,0.4,0.4", 0.5f)),
            roll(2, "high.jpg", 2, result(1, "0.2,0.2,0.4,0.4", 0.9f)),
            roll(3, "high-new.jpg", 3, result(1, "0.2,0.2,0.4,0.4", 0.9f)),
        )
        assertEquals("high-new.jpg", pickBestPicture(1, 1, rolls)!!.photoPath)
    }

    @Test
    fun pool_prefersRollShowingMostDice_andUnionsBoxes() {
        val rolls = listOf(
            roll(1, "one.jpg", 9, result(1, "0.1,0.1,0.2,0.2", 0.99f)),
            roll(2, "two.jpg", 1, result(1, "0.2,0.2,0.3,0.3"), result(1, "0.5,0.5,0.6,0.6")),
        )
        val pic = pickBestPicture(1, 3, rolls)!!
        assertEquals("two.jpg", pic.photoPath)
        // union 0.2..0.6 padded by 15% of 0.4 = 0.06
        assertEquals(0.14f, pic.box.left, 1e-4f)
        assertEquals(0.66f, pic.box.right, 1e-4f)
    }

    @Test
    fun parseBox_rejectsGarbage() {
        assertNull(parseBox(""))
        assertNull(parseBox("0.1,0.1,0.1,0.2"))
        assertEquals(0.5f, parseBox("0.1,0.2,0.6,0.7")!!.width, 1e-6f)
    }
}
