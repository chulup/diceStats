package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full recognition pass: locate each die ([DieDetector]) and read its value — from the detector
 * itself when it is a [ValueReadingDieDetector] (YOLO), else by counting pips ([PipCounter],
 * OpenCV) — DEVPLAN.md steps 2 + 3.
 */
class DieRecognizer(
    private val detector: DieDetector = ClassicalDieDetector(),
    private val pipCounter: PipCounter = PipCounter(),
) {
    suspend fun recognize(bitmap: Bitmap): List<DetectedDie> = withContext(Dispatchers.Default) {
        // Favor recall: surface every proposed region, with its value when
        // readable (null otherwise). The user prunes false positives on the
        // confirm screen rather than us dropping them automatically.
        val dice = (detector as? ValueReadingDieDetector)?.detectDice(bitmap)
            ?: detector.detect(bitmap).map { DetectedDie(value = null, boundingBox = it) }
        dice.map { die ->
            if (die.value != null) die else die.copy(value = pipCounter.count(bitmap, die.boundingBox))
        }
    }
}
