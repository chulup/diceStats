package xyz.chulup.dicestats.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.chulup.dicestats.recognition.internal.ColorFingerprintPipeline

/**
 * JVM tests for the colour-fingerprint identity path: [ColorFingerprintPipeline]
 * builds a signature from synthetic solid-colour crops and [DieIdentifier] matches
 * a query against known dice.
 */
class DieIdentificationTest {

    /** A [size]x[size] solid-colour image with one dark "pip" pixel at the centre. */
    private fun solid(size: Int, r: Int, g: Int, b: Int): IntArray {
        val px = IntArray(size * size) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }
        px[size / 2 * size + size / 2] = 0xFF000000.toInt() // pip — should be ignored
        return px
    }

    private fun signatureOf(r: Int, g: Int, b: Int): DieColorSignature {
        val size = 20
        val px = solid(size, r, g, b)
        return ColorFingerprintPipeline.signature(px, size, size, 0, 0, size, size)
            ?: error("expected a signature for ($r,$g,$b)")
    }

    @Test
    fun tooFewPixels_yieldsNoSignature() {
        val px = solid(3, 200, 30, 30) // 9 px < minSamples
        assertNull(ColorFingerprintPipeline.signature(px, 3, 3, 0, 0, 3, 3))
    }

    @Test
    fun distinctColors_areFarApart() {
        val red = signatureOf(200, 30, 30)
        val blue = signatureOf(30, 30, 200)
        assertTrue(
            DieColorSignature.distance(red, blue) > DieColorSignature.MATCH_MAX_DISTANCE,
        )
    }

    @Test
    fun identifiesMatchingColorAmongCandidates() {
        val candidates = listOf(
            DieIdentifier.Candidate(1L, signatureOf(200, 30, 30)),   // red
            DieIdentifier.Candidate(2L, signatureOf(30, 30, 200)),   // blue
            DieIdentifier.Candidate(3L, signatureOf(235, 235, 235)), // white
        )
        val identifier = DieIdentifier()

        val query = signatureOf(210, 40, 35) // a slightly different red
        val match = identifier.identify(query, candidates)

        assertNotNull(match)
        assertEquals(1L, match!!.dieId)
        assertTrue(match.confidence >= DieIdentifier.IDENTITY_CONFIRM_THRESHOLD)
    }

    @Test
    fun unknownColor_returnsNoMatch() {
        val candidates = listOf(DieIdentifier.Candidate(1L, signatureOf(30, 30, 200))) // blue only
        val match = DieIdentifier().identify(signatureOf(200, 30, 30), candidates) // query red
        assertNull(match)
    }

    @Test
    fun whiteAndColored_areDistinguished() {
        val white = signatureOf(235, 235, 235)
        val red = signatureOf(200, 30, 30)
        assertTrue(
            DieColorSignature.distance(white, red) > DieColorSignature.MATCH_MAX_DISTANCE,
        )
    }

    @Test
    fun encodeDecode_roundTrips() {
        val s = signatureOf(200, 30, 30)
        val decoded = DieColorSignature.decode(s.encode())
        assertEquals(s, decoded)
    }

    @Test
    fun merge_movesTowardNewSampleByWeight() {
        val a = DieColorSignature(0f, 0f, 0f)
        val b = DieColorSignature(1f, 1f, 1f)
        // Averaging one old sample with one new sample -> midpoint.
        val merged = DieColorSignature.merge(a, oldSamples = 1, new = b)
        assertEquals(0.5f, merged.chromaA, 1e-6f)
        assertEquals(0.5f, merged.lightness, 1e-6f)
    }
}
