package xyz.chulup.dicestats.data.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorAnglesTest {

    @Test
    fun flatPhoneLooksStraightDown() = assertEquals(0f, cameraTiltDegrees(floatArrayOf(0f, 0f, 9.81f))!!, 0.01f)

    @Test
    fun uprightPhoneIsNinetyDegrees() = assertEquals(90f, cameraTiltDegrees(floatArrayOf(0f, 9.81f, 0f))!!, 0.01f)

    @Test
    fun fortyFiveDegreesTilt() = assertEquals(45f, cameraTiltDegrees(floatArrayOf(0f, 6.937f, 6.937f))!!, 0.05f)

    @Test
    fun noGravityHasNoTilt() = assertNull(cameraTiltDegrees(floatArrayOf(0f, 0f, 0f)))

    @Test
    fun portraitUprightPointsUpAlongTopEdge() = assertEquals(0f, upInImageDegrees(floatArrayOf(0f, 9.81f, 0f)), 0.01f)
}
