package xyz.chulup.dicestats.feature.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.recognition.BoundingBox

/**
 * JVM tests for [DetectionViewModel]'s pure decision logic — the state derivations that
 * gate persistence ([DetectionUiState.Ready.canSave], [recentDice], [DieAssignment.hasValue])
 * and the framework-free helpers ([nextActiveDie], [sampleSizeFor]). The ViewModel itself
 * pulls in Android (Bitmap/JSON) + viewModelScope, so only this extracted logic is unit-tested.
 */
class DetectionLogicTest {

    private val box = BoundingBox(0f, 0f, 1f, 1f)

    private fun die(id: Long, faces: Int = 6, createdAt: Long = 0) =
        DieEntity(id = id, name = "d$faces", faces = faces, createdAt = createdAt)

    private fun assignment(value: Int, dieId: Long?, recognizedValue: Int? = value, edited: Boolean = false) =
        DieAssignment(
            boundingBox = box,
            value = value,
            recognizedValue = recognizedValue,
            dieId = dieId,
            edited = edited,
        )

    private fun ready(dice: List<DieAssignment>, registered: List<DieEntity>) =
        DetectionUiState.Ready(
            photoPath = "p.jpg",
            aspectRatio = 1f,
            dice = dice,
            registeredDice = registered,
        )

    // --- canSave ----------------------------------------------------------------

    @Test
    fun canSave_isFalse_whenNoDice() {
        assertFalse(ready(emptyList(), listOf(die(1))).canSave)
    }

    @Test
    fun canSave_isTrue_whenEveryDieIsAssignedWithAValidValue() {
        val state = ready(
            dice = listOf(assignment(value = 3, dieId = 1L)),
            registered = listOf(die(1)),
        )
        assertTrue(state.canSave)
    }

    @Test
    fun canSave_isFalse_whenAValueIsNotAFaceOfItsDie() {
        // 7 isn't a face of a d6.
        val state = ready(
            dice = listOf(assignment(value = 7, dieId = 1L)),
            registered = listOf(die(1, faces = 6)),
        )
        assertFalse(state.canSave)
    }

    @Test
    fun canSave_isFalse_whenADieIsUnassigned() {
        val state = ready(
            dice = listOf(assignment(value = 3, dieId = null)),
            registered = listOf(die(1)),
        )
        assertFalse(state.canSave)
    }

    @Test
    fun canSave_isFalse_whenAssignedDieIsNotRegistered() {
        val state = ready(
            dice = listOf(assignment(value = 3, dieId = 99L)),
            registered = listOf(die(1)),
        )
        assertFalse(state.canSave)
    }

    // --- recentDice -------------------------------------------------------------

    @Test
    fun recentDice_ordersMostRecentlyCreatedFirst() {
        val state = ready(
            dice = emptyList(),
            registered = listOf(
                die(1, createdAt = 100),
                die(2, createdAt = 300),
                die(3, createdAt = 200),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), state.recentDice.map { it.id })
    }

    // --- DieAssignment.hasValue -------------------------------------------------

    @Test
    fun hasValue_isTrue_whenRecognized() {
        assertTrue(assignment(value = 4, dieId = null, recognizedValue = 4).hasValue)
    }

    @Test
    fun hasValue_isTrue_whenUserEdited() {
        assertTrue(assignment(value = 4, dieId = null, recognizedValue = null, edited = true).hasValue)
    }

    @Test
    fun hasValue_isFalse_whenUnreadAndUntouched() {
        assertFalse(assignment(value = 1, dieId = null, recognizedValue = null, edited = false).hasValue)
    }

    // --- nextActiveDie ----------------------------------------------------------

    @Test
    fun nextActiveDie_advancesToTheFollowingDie() {
        val order = listOf(die(1), die(2), die(3))
        assertEquals(2L, nextActiveDie(order, current = 1L))
    }

    @Test
    fun nextActiveDie_wrapsPastTheEnd() {
        val order = listOf(die(1), die(2), die(3))
        assertEquals(1L, nextActiveDie(order, current = 3L))
    }

    @Test
    fun nextActiveDie_fallsBackToFirst_whenCurrentIsNotInOrder() {
        val order = listOf(die(1), die(2), die(3))
        assertEquals(1L, nextActiveDie(order, current = 99L))
    }

    @Test
    fun nextActiveDie_isNull_whenNoDice() {
        assertNull(nextActiveDie(emptyList(), current = 1L))
    }

    // --- sampleSizeFor ----------------------------------------------------------

    @Test
    fun sampleSizeFor_isOne_whenAlreadyWithinTarget() {
        assertEquals(1, sampleSizeFor(width = 1280, height = 960, targetMaxEdge = 1280))
        assertEquals(1, sampleSizeFor(width = 640, height = 480, targetMaxEdge = 1280))
    }

    @Test
    fun sampleSizeFor_halvesUntilLongestEdgeNearsTarget() {
        // 4000 -> /2 = 2000 (still >= 1280, but the next halving would undershoot).
        assertEquals(2, sampleSizeFor(width = 4000, height = 3000, targetMaxEdge = 1280))
        // 5120 -> 2560 -> 1280, two halvings.
        assertEquals(4, sampleSizeFor(width = 5120, height = 3840, targetMaxEdge = 1280))
    }
}
