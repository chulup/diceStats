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
        detector.detect(bitmap).map { box ->
            DetectedDie(value = pipCounter.count(bitmap, box), boundingBox = box)
        }
    }
}
