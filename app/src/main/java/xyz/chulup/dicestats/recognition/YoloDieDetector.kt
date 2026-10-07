package xyz.chulup.dicestats.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_dnn.Net
import xyz.chulup.dicestats.recognition.internal.YoloDetectionPipeline

/**
 * On-device [ValueReadingDieDetector] backed by a YOLO model run through OpenCV's `dnn` module
 * ([YoloDetectionPipeline]). The intended replacement for [ClassicalDieDetector] once the
 * `yolo26n-dice.onnx` model has been trained (see `design-records/2026-07-08-yolo-detector-opencv-dnn.md`).
 * Each class is a top-face value ([classValues]), so detection also reads the die.
 *
 * The model ships as an app asset ([MODEL_ASSET]). It is loaded lazily on the first [detect] and
 * cached. **If the asset is absent** — as it is until the model is trained — [detect] returns an
 * empty list and logs once, so `RecognitionModule` can fall back to the classical detector rather
 * than crash. Use [isModelPresent] to make that choice at wiring time.
 *
 * OpenCV natives are already loaded app-wide in `DiceStatsApp` (and in tests' `@BeforeClass`);
 * the `dnn` module rides on the same bytedeco build, so no extra initialization is needed.
 */
class YoloDieDetector(
    private val context: Context,
    private val assetName: String = MODEL_ASSET,
    private val params: YoloDetectionPipeline.Params = YoloDetectionPipeline.Params(),
    /** Die value per model class id; ids outside the list yield a null value. */
    private val classValues: List<Int> = CLASS_VALUES,
) : ValueReadingDieDetector {

    @Volatile private var net: Net? = null
    @Volatile private var loadAttempted = false

    override suspend fun detect(bitmap: Bitmap): List<BoundingBox> = detectDice(bitmap).map { it.boundingBox }

    override suspend fun detectDice(bitmap: Bitmap): List<DetectedDie> = withContext(Dispatchers.Default) {
        val model = ensureNet() ?: return@withContext emptyList()

        // ARGB_8888 is stored R,G,B,A in memory, matching CV_8UC4 — copy straight in (as PipCounter does).
        val rgba = Mat(bitmap.height, bitmap.width, opencv_core.CV_8UC4)
        bitmap.copyPixelsToBuffer(rgba.data().capacity(bitmap.byteCount.toLong()).asByteBuffer())
        try {
            YoloDetectionPipeline.detect(rgba, model, params).map {
                DetectedDie(value = classValues.getOrNull(it.classId), boundingBox = it.box)
            }
        } finally {
            rgba.release()
        }
    }

    /** Load and cache the [Net] on first use; null (once) when the asset is missing or unparseable. */
    private fun ensureNet(): Net? {
        net?.let { return it }
        synchronized(this) {
            if (!loadAttempted) {
                loadAttempted = true
                net = loadNet()
            }
        }
        return net
    }

    private fun loadNet(): Net? = runCatching {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        // Load from the in-memory buffer (Android assets aren't real files); no temp copy needed.
        opencv_dnn.readNetFromONNX(BytePointer(*bytes), bytes.size.toLong()).also {
            check(!it.isNull && !it.empty()) { "readNetFromONNX returned an empty net" }
        }
    }.onFailure {
        Log.w(TAG, "YOLO model '$assetName' not loaded; detector will yield no boxes", it)
    }.getOrNull()

    companion object {
        /** App-asset filename of the exported ONNX model. Drop the trained model here to enable YOLO. */
        const val MODEL_ASSET = "yolo26n-dice.onnx"

        /** Training class order `d6-1`..`d6-6` (`../training/export_yolo.py` CLASSES). */
        val CLASS_VALUES = listOf(1, 2, 3, 4, 5, 6)

        private const val TAG = "YoloDieDetector"

        /** True when the model asset is present, so the model can actually detect. */
        fun isModelPresent(context: Context, assetName: String = MODEL_ASSET): Boolean =
            runCatching { context.assets.open(assetName).use { } }.isSuccess
    }
}
