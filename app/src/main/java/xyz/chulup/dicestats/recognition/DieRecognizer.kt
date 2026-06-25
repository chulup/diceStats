package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full recognition pass: locate each die ([DieDetector], classical CV) and read
 * its pip value ([PipCounter], OpenCV) — DEVPLAN.md steps 2 + 3.
 */
class DieRecognizer(
    private val detector: DieDetector = ClassicalDieDetector(),
    private val pipCounter: PipCounter = PipCounter(),
) {
    suspend fun recognize(bitmap: Bitmap): List<DetectedDie> = withContext(Dispatchers.Default) {
        // A die face always shows 1..6 pips; regions with no readable pips are
        // treated as not-a-die, which filters out small false positives (wood
        // grain, pencil tips) that the region detector may propose.
        detector.detect(bitmap).mapNotNull { box ->
            pipCounter.count(bitmap, box)?.let { value -> DetectedDie(value = value, boundingBox = box) }
        }
    }
}
