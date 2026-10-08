package xyz.chulup.dicestats.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import xyz.chulup.dicestats.recognition.ClassicalDieDetector
import xyz.chulup.dicestats.recognition.DieDetector
import xyz.chulup.dicestats.recognition.DieRecognizer
import xyz.chulup.dicestats.recognition.TwoStageRecognizer
import xyz.chulup.dicestats.recognition.ValueClassifier
import xyz.chulup.dicestats.recognition.YoloDieDetector
import javax.inject.Singleton

/**
 * Wires the die detector used by recognition. The trained YOLO model
 * ([YoloDieDetector.MODEL_ASSETS]) is the intended detector; while it is absent (during the
 * training period) this falls back to the classical CV detector so the app keeps working.
 *
 * The asset's presence is the switch: drop the trained `.onnx` into `app/src/main/assets/` and
 * YOLO takes over on the next build — no code change. See the design record dated 2026-07-08.
 * With several model assets present, the first drives recognition and the rest run as
 * comparison shadows ([DieRecognizer.runShadows]), as do two-stage detector + value classifier
 * pairs ([TwoStageRecognizer]); the classifiers also read the confirmed dice on save.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecognitionModule {

    @Provides
    @Singleton
    fun provideDieDetector(@ApplicationContext context: Context): DieDetector =
        presentModels(context).firstOrNull()?.let { YoloDieDetector(context, it) } ?: ClassicalDieDetector()

    /**
     * Taken photos are read by a 2-of-3 vote of the main YOLO model and both two-stage pipelines
     * when all are bundled ([DieRecognizer.recognizeVoted]); one-stage extras stay comparison shadows.
     */
    @Provides
    @Singleton
    fun provideDieRecognizer(@ApplicationContext context: Context, detector: DieDetector): DieRecognizer {
        val present = { asset: String -> YoloDieDetector.isModelPresent(context, asset) }
        val classifiers = TwoStageRecognizer.CLASSIFIER_ASSETS.filter(present).map { ValueClassifier(context, it) }
        val twoStage = TwoStageRecognizer.DETECTOR_ASSETS.zip(TwoStageRecognizer.CLASSIFIER_ASSETS)
            .filter { (det, cls) -> present(det) && present(cls) }
            .map { (det, cls) ->
                TwoStageRecognizer(
                    YoloDieDetector(context, det, classValues = emptyList()),
                    classifiers.first { it.assetName == cls },
                )
            }
        val voters = listOfNotNull(detector as? YoloDieDetector) + twoStage
        val voting = voters.size >= 3
        return DieRecognizer(
            detector,
            shadows = presentModels(context).drop(1).map { YoloDieDetector(context, it) },
            twoStage = if (voting) emptyList() else twoStage,
            classifiers = classifiers,
            voters = if (voting) voters else emptyList(),
        )
    }

    private fun presentModels(context: Context): List<String> =
        YoloDieDetector.MODEL_ASSETS.filter { YoloDieDetector.isModelPresent(context, it) }
}
