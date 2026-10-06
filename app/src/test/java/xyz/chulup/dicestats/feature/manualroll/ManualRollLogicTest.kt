package xyz.chulup.dicestats.feature.manualroll

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.chulup.dicestats.data.db.DieEntity

class ManualRollLogicTest {

    private fun die(id: Long, name: String, count: Int = 1) =
        DieEntity(id = id, name = name, count = count, createdAt = id)

    private val red = die(1, "Red", count = 3)
    private val blue = die(2, "Blue", count = 2)
    private val green = die(3, "Green") // single die
    private val dice = listOf(red, blue, green)

    @Test
    fun capacityRemaining_countsPerDie() {
        val results = listOf(ManualResult(1, 2), ManualResult(1, 3))
        assertEquals(1, capacityRemaining(red, results)) // pool of 3, two used
        assertEquals(2, capacityRemaining(blue, results)) // untouched
        assertEquals(1, capacityRemaining(green, emptyList()))
        assertEquals(0, capacityRemaining(green, listOf(ManualResult(3, 4))))
    }

    @Test
    fun appendOrReplace_appendsWhileUnderCapacity() {
        var results = emptyList<ManualResult>()
        results = appendOrReplace(results, red, 2)
        results = appendOrReplace(results, red, 3)
        results = appendOrReplace(results, red, 4)
        assertEquals(listOf(2, 3, 4), results.map { it.value })
    }

    @Test
    fun appendOrReplace_overwritesLastAtCapacity() {
        // A single die at capacity: a new tap corrects its value, not adds another.
        val results = appendOrReplace(listOf(ManualResult(3, 4)), green, 6)
        assertEquals(listOf(ManualResult(3, 6)), results)
    }

    @Test
    fun appendOrReplace_pooledOverwriteHitsTheLastMember() {
        val full = listOf(ManualResult(2, 1), ManualResult(2, 5))
        val replaced = appendOrReplace(full, blue, 6)
        assertEquals(listOf(1, 6), replaced.map { it.value }) // last blue overwritten
    }

    @Test
    fun groupForDisplay_groupsByDiePreservingFirstAppearance() {
        val results = listOf(
            ManualResult(2, 1), // Blue
            ManualResult(1, 2), // Red
            ManualResult(2, 5), // Blue again
            ManualResult(1, 3), // Red
            ManualResult(1, 4), // Red
        )
        val groups = groupForDisplay(results, dice)
        assertEquals(listOf("Blue", "Red"), groups.map { it.die.name })
        assertEquals(listOf(1, 5), groups[0].values)
        assertEquals(listOf(2, 3, 4), groups[1].values)
    }

    @Test
    fun groupForDisplay_dropsResultsWhoseDieIsUnknown() {
        val results = listOf(ManualResult(1, 2), ManualResult(99, 5))
        val groups = groupForDisplay(results, dice)
        assertEquals(1, groups.size)
        assertEquals("Red", groups[0].die.name)
    }
}
