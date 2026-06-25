package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap

/**
 * Locates every d6 in a photo and returns one [BoundingBox] per die.
 *
 * This is the clean boundary the rest of the app depends on (per DESIGN.md's
 * `:recognition` module). The current implementation is classical CV
 * ([ClassicalDieDetector]); it can be replaced with an on-device model later
 * without changing callers.
 *
 * Implementations are expected to be CPU-bound and are called off the main
 * thread by the caller.
 */
interface DieDetector {
    suspend fun detect(bitmap: Bitmap): List<BoundingBox>
}
