package xyz.chulup.dicestats.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionMappingTest {

    private val rawW = 4000
    private val rawH = 3000

    /** The full image maps to the whole raw rect regardless of display rotation. */
    @Test
    fun fullFrame_mapsToWholeRawImage_allRotations() {
        val full = BoundingBox(0f, 0f, 1f, 1f)
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(
                "rotation=$rotation",
                RawRect(0, 0, rawW, rawH),
                mapOrientedRegionToRaw(full, rawW, rawH, rotation),
            )
        }
    }

    @Test
    fun rotation0_isIdentity() {
        val rightHalf = BoundingBox(0.5f, 0f, 1f, 1f)
        assertEquals(
            RawRect(2000, 0, 4000, 3000),
            mapOrientedRegionToRaw(rightHalf, rawW, rawH, 0),
        )
    }

    // For 90/270 the displayed image swaps axes (oriented = 3000x4000); the oriented
    // top-left quarter maps to a corner of the raw landscape image.
    @Test
    fun rotation90_topLeftQuarter_mapsToRawBottomLeft() {
        val topLeft = BoundingBox(0f, 0f, 0.5f, 0.5f)
        assertEquals(
            RawRect(0, 1500, 2000, 3000),
            mapOrientedRegionToRaw(topLeft, rawW, rawH, 90),
        )
    }

    @Test
    fun rotation270_topLeftQuarter_mapsToRawTopRight() {
        val topLeft = BoundingBox(0f, 0f, 0.5f, 0.5f)
        assertEquals(
            RawRect(2000, 0, 4000, 1500),
            mapOrientedRegionToRaw(topLeft, rawW, rawH, 270),
        )
    }

    @Test
    fun rotation180_topLeftQuarter_mapsToRawBottomRight() {
        val topLeft = BoundingBox(0f, 0f, 0.5f, 0.5f)
        assertEquals(
            RawRect(2000, 1500, 4000, 3000),
            mapOrientedRegionToRaw(topLeft, rawW, rawH, 180),
        )
    }
}
