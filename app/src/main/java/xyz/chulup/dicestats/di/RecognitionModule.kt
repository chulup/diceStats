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
import xyz.chulup.dicestats.recognition.YoloDieDetector
import javax.inject.Singleton

/**
 * Wires the die detector used by recognition. The trained YOLO model
 * ([YoloDieDetector.MODEL_ASSET]) is the intended detector; while it is absent (during the
 * training period) this falls back to the classical CV detector so the app keeps working.
 *
 * The asset's presence is the switch: drop the trained `.onnx` into `app/src/main/assets/` and
 * YOLO takes over on the next build — no code change. See the design record dated 2026-07-08.
 */
@Module
@InstallIn(SingletonComponent::class)
object RecognitionModule {

    @Provides
    @Singleton
    fun provideDieDetector(@ApplicationContext context: Context): DieDetector =
        if (YoloDieDetector.isModelPresent(context)) YoloDieDetector(context) else ClassicalDieDetector()

    @Provides
    @Singleton
    fun provideDieRecognizer(detector: DieDetector): DieRecognizer = DieRecognizer(detector)
}
