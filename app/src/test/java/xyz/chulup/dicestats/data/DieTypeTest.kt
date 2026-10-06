package xyz.chulup.dicestats.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieTypeTest {

    @Test
    fun faceValues_perType() {
        assertEquals((1..4).toList(), DieType.D4.faceValues)
        assertEquals((1..6).toList(), DieType.D6.faceValues)
        assertEquals((1..8).toList(), DieType.D8.faceValues)
        assertEquals((1..10).toList(), DieType.D10.faceValues)
        assertEquals((1..12).toList(), DieType.D12.faceValues)
        assertEquals((1..20).toList(), DieType.D20.faceValues)
        assertEquals(listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90), DieType.D100.faceValues)
    }

    @Test
    fun sidesAndStep() {
        assertEquals(6, DieType.D6.sides)
        assertEquals(1, DieType.D6.step)
        // d100 has ten faces spaced by ten.
        assertEquals(10, DieType.D100.sides)
        assertEquals(10, DieType.D100.step)
    }

    @Test
    fun expectedMean_perType() {
        assertEquals(3.5, DieType.D6.expectedMean, 1e-9)
        assertEquals(4.5, DieType.D8.expectedMean, 1e-9)
        assertEquals(5.5, DieType.D10.expectedMean, 1e-9)
        assertEquals(10.5, DieType.D20.expectedMean, 1e-9)
        assertEquals(45.0, DieType.D100.expectedMean, 1e-9)
    }

    @Test
    fun faceIndex_withinRange() {
        assertEquals(0, DieType.D6.faceIndex(1))
        assertEquals(5, DieType.D6.faceIndex(6))
        assertEquals(0, DieType.D100.faceIndex(0))
        assertEquals(5, DieType.D100.faceIndex(50))
        assertEquals(9, DieType.D100.faceIndex(90))
    }

    @Test
    fun faceIndex_outsideRange_isNull() {
        assertNull(DieType.D6.faceIndex(0))
        assertNull(DieType.D6.faceIndex(7))
        assertNull(DieType.D6.faceIndex(-1))
        // In range numerically but not on a face (d100 steps by ten).
        assertNull(DieType.D100.faceIndex(5))
        assertNull(DieType.D100.faceIndex(95))
    }

    @Test
    fun isValidValue_matchesFaceIndex() {
        assertTrue(DieType.D20.isValidValue(20))
        assertFalse(DieType.D20.isValidValue(21))
        assertTrue(DieType.D100.isValidValue(30))
        assertFalse(DieType.D100.isValidValue(35))
    }

    @Test
    fun d4AndD12_facesAndValidity() {
        assertEquals(4, DieType.D4.sides)
        assertEquals(2.5, DieType.D4.expectedMean, 1e-9)
        assertTrue(DieType.D4.isValidValue(4))
        assertFalse(DieType.D4.isValidValue(5))
        assertEquals(12, DieType.D12.sides)
        assertEquals(6.5, DieType.D12.expectedMean, 1e-9)
        assertTrue(DieType.D12.isValidValue(12))
        assertFalse(DieType.D12.isValidValue(13))
    }

    @Test
    fun fromFaces_mapsTheDbFacesNumber() {
        assertEquals(DieType.D4, DieType.fromFaces(4))
        assertEquals(DieType.D6, DieType.fromFaces(6))
        assertEquals(DieType.D8, DieType.fromFaces(8))
        assertEquals(DieType.D10, DieType.fromFaces(10))
        assertEquals(DieType.D12, DieType.fromFaces(12))
        assertEquals(DieType.D20, DieType.fromFaces(20))
        assertEquals(DieType.D100, DieType.fromFaces(100))
    }

    @Test
    fun fromFaces_unknownCount_fallsBackToDefault() {
        assertEquals(DieType.DEFAULT, DieType.fromFaces(0))
        assertEquals(DieType.DEFAULT, DieType.fromFaces(13))
    }
}
