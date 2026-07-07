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

    private fun die(id: Long, faces: Int = 6, createdAt: Long = 0, count: Int = 1) =
        DieEntity(id = id, name = "d$faces", faces = faces, count = count, createdAt = createdAt)

    private fun assignment(
        value: Int,
        dieId: Long?,
        recognizedValue: Int? = value,
        edited: Boolean = false,
        dieIdConfidence: Float? = null,
    ) = DieAssignment(
        boundingBox = box,
        value = value,
        recognizedValue = recognizedValue,
        dieId = dieId,
        dieIdConfidence = dieIdConfidence,
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

    @Test
    fun canSave_isFalse_whenAPoolCarriesMoreBoxesThanItsCount() {
        // Four boxes on a 3-die pool is a definite user error.
        val pool = die(1, count = 3)
        val state = ready(
            dice = List(4) { assignment(value = 2, dieId = 1L) },
            registered = listOf(pool),
        )
        assertFalse(state.canSave)
    }

    @Test
    fun canSave_isTrue_whenAPoolIsAtOrUnderItsCount() {
        // count is an upper bound: fewer dice than the pool holds is a normal roll.
        val pool = die(1, count = 3)
        assertTrue(ready(List(3) { assignment(value = 2, dieId = 1L) }, listOf(pool)).canSave)
        assertTrue(ready(List(2) { assignment(value = 2, dieId = 1L) }, listOf(pool)).canSave)
    }

    // --- atCapacityDieIds ---------------------------------------------------------

    @Test
    fun atCapacityDieIds_flagsFullDiceAndPools() {
        val state = ready(
            dice = listOf(
                assignment(value = 1, dieId = 1L),
                assignment(value = 2, dieId = 2L),
                assignment(value = 3, dieId = 2L),
            ),
            registered = listOf(die(1), die(2, count = 3), die(3)),
        )
        // Die 1 (single) is full; pool 2 has one slot left; die 3 is untouched.
        assertEquals(setOf(1L), state.atCapacityDieIds)
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

    @Test
    fun nextActiveDie_skipsDiceAtCapacity() {
        val order = listOf(die(1), die(2), die(3))
        val counts = mapOf(2L to 1) // die 2 (count 1) is full
        assertEquals(3L, nextActiveDie(order, current = 1L, assignedCounts = counts))
    }

    @Test
    fun nextActiveDie_isNull_whenEveryDieIsFull() {
        val order = listOf(die(1), die(2, count = 2))
        val counts = mapOf(1L to 1, 2L to 2)
        assertNull(nextActiveDie(order, current = 1L, assignedCounts = counts))
    }

    // --- activeDieAfterAssignment -------------------------------------------------

    @Test
    fun activeDie_staysOnPool_untilItsCapacityIsUsed() {
        // Risk red ×3 with one box assigned: two slots left, so the highlight stays.
        val order = listOf(die(1, count = 3), die(2, count = 2))
        assertEquals(1L, activeDieAfterAssignment(order, justAssigned = 1L, assignedCounts = mapOf(1L to 1)))
        assertEquals(1L, activeDieAfterAssignment(order, justAssigned = 1L, assignedCounts = mapOf(1L to 2)))
    }

    @Test
    fun activeDie_advances_whenThePoolFills() {
        val order = listOf(die(1, count = 3), die(2, count = 2))
        assertEquals(2L, activeDieAfterAssignment(order, justAssigned = 1L, assignedCounts = mapOf(1L to 3)))
    }

    @Test
    fun activeDie_advancesImmediately_forSingleDice() {
        val order = listOf(die(1), die(2))
        assertEquals(2L, activeDieAfterAssignment(order, justAssigned = 1L, assignedCounts = mapOf(1L to 1)))
    }

    // --- capAutoAssignments ---------------------------------------------------------

    @Test
    fun capAutoAssignments_keepsAssignmentsWithinCapacity() {
        val pool = die(1, count = 2)
        val input = listOf(
            assignment(value = 1, dieId = 1L, dieIdConfidence = 0.9f),
            assignment(value = 2, dieId = 1L, dieIdConfidence = 0.7f),
        )
        assertEquals(input, capAutoAssignments(input, listOf(pool)))
    }

    @Test
    fun capAutoAssignments_unassignsLowestConfidenceOverflow() {
        val pool = die(1, count = 2)
        val input = listOf(
            assignment(value = 1, dieId = 1L, dieIdConfidence = 0.9f),
            assignment(value = 2, dieId = 1L, dieIdConfidence = 0.6f),
            assignment(value = 3, dieId = 1L, dieIdConfidence = 0.8f),
        )
        val capped = capAutoAssignments(input, listOf(pool))
        assertEquals(1L, capped[0].dieId)
        assertNull(capped[1].dieId) // the weakest guess loses its assignment
        assertNull(capped[1].dieIdConfidence)
        assertEquals(1L, capped[2].dieId)
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

    // --- add-a-missed-die geometry ----------------------------------------------

    @Test
    fun median_handlesOddAndEvenCounts() {
        assertEquals(2f, median(listOf(3f, 1f, 2f)), 1e-6f)
        assertEquals(2.5f, median(listOf(1f, 2f, 3f, 4f)), 1e-6f)
        assertEquals(0f, median(emptyList()), 1e-6f)
    }

    @Test
    fun boxIou_isZeroWhenDisjoint_andOneWhenIdentical() {
        val a = BoundingBox(0f, 0f, 0.2f, 0.2f)
        val b = BoundingBox(0.5f, 0.5f, 0.7f, 0.7f)
        assertEquals(0f, boxIou(a, b), 1e-6f)
        assertEquals(1f, boxIou(a, a), 1e-6f)
    }

    @Test
    fun detectionWindow_sizesToAFewDiceAcross_whenDiceExist() {
        // A single 0.10-wide, 0.20-tall die → half-extents 0.15 × 0.30 (×1.5), clamped ≤0.5.
        val die = BoundingBox(0.40f, 0.30f, 0.50f, 0.50f)
        val w = detectionWindowAround(cx = 0.45f, cy = 0.40f, dice = listOf(die), aspectRatio = 1f)
        assertEquals(0.30f, w.left, 1e-5f)
        assertEquals(0.60f, w.right, 1e-5f)
        assertEquals(0.10f, w.top, 1e-5f)
        assertEquals(0.70f, w.bottom, 1e-5f)
    }

    @Test
    fun detectionWindow_fallsBackToFrameFraction_keptPixelSquare_whenNoDice() {
        // No dice: half-width 0.10, half-height 0.10 * aspectRatio (2.0) = 0.20.
        val w = detectionWindowAround(cx = 0.5f, cy = 0.5f, dice = emptyList(), aspectRatio = 2f)
        assertEquals(0.40f, w.left, 1e-5f)
        assertEquals(0.60f, w.right, 1e-5f)
        assertEquals(0.30f, w.top, 1e-5f)
        assertEquals(0.70f, w.bottom, 1e-5f)
    }

    @Test
    fun detectionWindow_clipsAtTheEdgeForCornerTaps() {
        val die = BoundingBox(0f, 0f, 0.10f, 0.10f)
        val w = detectionWindowAround(cx = 0.02f, cy = 0.02f, dice = listOf(die), aspectRatio = 1f)
        assertEquals(0f, w.left, 1e-5f)
        assertEquals(0f, w.top, 1e-5f)
    }

    @Test
    fun mapBoxFromWindow_placesLocalBoxIntoFullImageCoords() {
        val window = BoundingBox(0.20f, 0.40f, 0.60f, 0.80f) // 0.40 × 0.40
        val local = BoundingBox(0.25f, 0.50f, 0.75f, 1.0f)
        val full = mapBoxFromWindow(local, window)
        assertEquals(0.20f + 0.25f * 0.40f, full.left, 1e-5f)
        assertEquals(0.40f + 0.50f * 0.40f, full.top, 1e-5f)
        assertEquals(0.20f + 0.75f * 0.40f, full.right, 1e-5f)
        assertEquals(0.40f + 1.0f * 0.40f, full.bottom, 1e-5f)
    }

    @Test
    fun pickAddedDetection_prefersTheBoxContainingTheTap() {
        val near = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f) // contains (0.45,0.45)
        val far = BoundingBox(0.80f, 0.80f, 0.90f, 0.90f)
        val pick = pickAddedDetection(
            listOf(far, near), values = listOf(3, 3), existing = emptyList(), cx = 0.45f, cy = 0.45f,
        )
        assertEquals(1, pick)
    }

    @Test
    fun pickAddedDetection_dropsDuplicatesOfExistingDice() {
        val dup = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f)
        val existing = listOf(BoundingBox(0.40f, 0.40f, 0.50f, 0.50f))
        assertNull(pickAddedDetection(listOf(dup), values = listOf(3), existing, cx = 0.45f, cy = 0.45f))
    }

    @Test
    fun pickAddedDetection_fallsToNearestWhenNoneContainTap() {
        val a = BoundingBox(0.10f, 0.10f, 0.20f, 0.20f) // centre (0.15,0.15)
        val b = BoundingBox(0.60f, 0.60f, 0.70f, 0.70f) // centre (0.65,0.65)
        val pick = pickAddedDetection(
            listOf(a, b), values = listOf(3, 3), existing = emptyList(), cx = 0.62f, cy = 0.62f,
        )
        assertEquals(1, pick)
    }

    @Test
    fun pickAddedDetection_rejectsPipSizedSpecksNearerThanTheDie() {
        // Reproduces the white-die log: a tiny unread pip nearer the tap than the real die,
        // with existing dice at the same physical scale as the one being added.
        val existing = listOf(BoundingBox(0.30f, 0.30f, 0.341f, 0.324f)) // die ≈ 0.041 × 0.024
        val pip = BoundingBox(0.590f, 0.553f, 0.608f, 0.566f) // 0.018 × 0.013, a pip
        val die = BoundingBox(0.594f, 0.571f, 0.635f, 0.595f) // 0.041 × 0.024
        val pick = pickAddedDetection(
            listOf(pip, die), values = listOf(null, 3), existing, cx = 0.581f, cy = 0.580f,
        )
        assertEquals(1, pick) // the die, not the closer pip
    }

    @Test
    fun pickAddedDetection_prefersAReadDieOverAnUnreadBlobOfSimilarSize() {
        val existing = listOf(BoundingBox(0.0f, 0.0f, 0.10f, 0.10f))
        val unread = BoundingBox(0.40f, 0.40f, 0.50f, 0.50f) // nearer the tap
        val read = BoundingBox(0.55f, 0.55f, 0.65f, 0.65f)
        val pick = pickAddedDetection(
            listOf(unread, read), values = listOf(null, 4), existing, cx = 0.48f, cy = 0.48f,
        )
        assertEquals(1, pick)
    }

    @Test
    fun translateBoxClamped_shiftsWithinTheImage() {
        val box = BoundingBox(0.40f, 0.40f, 0.50f, 0.60f) // 0.10 × 0.20
        val moved = translateBoxClamped(box, dx = 0.10f, dy = -0.05f)
        assertEquals(0.50f, moved.left, 1e-5f)
        assertEquals(0.35f, moved.top, 1e-5f)
        assertEquals(0.60f, moved.right, 1e-5f)
        assertEquals(0.55f, moved.bottom, 1e-5f)
    }

    @Test
    fun translateBoxClamped_parksAgainstTheEdge_preservingSize() {
        val box = BoundingBox(0.80f, 0.80f, 0.90f, 0.95f) // 0.10 × 0.15
        val moved = translateBoxClamped(box, dx = 0.50f, dy = 0.50f) // way past bottom-right
        assertEquals(1f, moved.right, 1e-5f)
        assertEquals(1f, moved.bottom, 1e-5f)
        assertEquals(0.10f, moved.width, 1e-5f)
        assertEquals(0.15f, moved.height, 1e-5f)
    }

    @Test
    fun pickAddedDetection_returnsNull_whenOnlyPipSizedSpecksRemain() {
        // A white die that never segments: only pip specks come back → caller uses a placeholder.
        val existing = listOf(BoundingBox(0.10f, 0.10f, 0.20f, 0.20f))
        val pips = listOf(
            BoundingBox(0.590f, 0.553f, 0.608f, 0.566f),
            BoundingBox(0.609f, 0.558f, 0.629f, 0.573f),
        )
        assertNull(pickAddedDetection(pips, values = listOf(null, null), existing, cx = 0.60f, cy = 0.56f))
    }
}
