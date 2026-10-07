package xyz.chulup.dicestats.recognition

import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Scalar
import xyz.chulup.dicestats.recognition.internal.YoloDetectionPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Verifies the pure [YoloDetectionPipeline.decode] geometry (letterbox inverse, layout handling,
 * NMS) against hand-built output tensors — no trained model required. Locks the math now so that
 * when the real `yolo26n-dice.onnx` lands, only its output *shape* needs confirming, not the decode.
 *
 * Fixture case: a 100x50 source letterboxed into 640x640 ⇒ scale 6.4, padX 0, padY 160. A source
 * box (10,10)-(30,20) becomes center (128,256) size (128,64) in input pixels, i.e. normalized
 * (0.10,0.20)-(0.30,0.40).
 */
class YoloDecodeTest {

    private val params = YoloDetectionPipeline.Params(confThreshold = 0.25f, iouThreshold = 0.45f)

    @Test
    fun decodesRawLayoutAndUndoesLetterbox() {
        // RAW [nFeat=5 (cx,cy,w,h + 1 class), anchors=20]; only anchor 0 is above threshold.
        val out = Mat(5, 20, opencv_core.CV_32F, Scalar(0.0))
        putBox(out, anchor = 0, cx = 128f, cy = 256f, w = 128f, h = 64f, scoreFeature = 4, score = 0.9f)

        val boxes = YoloDetectionPipeline.decode(out, 100, 50, 6.4, 0, 160, params)
        out.release()

        assertEquals(1, boxes.size)
        assertBox(boxes[0].box, 0.10f, 0.20f, 0.30f, 0.40f)
    }

    @Test
    fun decodesEndToEndLayout() {
        // END_TO_END [rows=8, 6 = x1,y1,x2,y2,conf,cls]; input-pixel xyxy for the same box.
        val out = Mat(8, 6, opencv_core.CV_32F, Scalar(0.0))
        val idx = out.createIndexer<FloatIndexer>(true)
        idx.put(0L, 0L, 64f); idx.put(0L, 1L, 224f); idx.put(0L, 2L, 192f); idx.put(0L, 3L, 288f)
        idx.put(0L, 4L, 0.8f); idx.put(0L, 5L, 0f)
        idx.release()

        val boxes = YoloDetectionPipeline.decode(out, 100, 50, 6.4, 0, 160, params)
        out.release()

        assertEquals(1, boxes.size)
        assertBox(boxes[0].box, 0.10f, 0.20f, 0.30f, 0.40f)
    }

    @Test
    fun suppressesOverlappingRawBoxes() {
        // Two near-identical boxes at anchors 0 and 1; NMS should keep only the higher-scored one.
        val out = Mat(5, 20, opencv_core.CV_32F, Scalar(0.0))
        putBox(out, anchor = 0, cx = 128f, cy = 256f, w = 128f, h = 64f, scoreFeature = 4, score = 0.9f)
        putBox(out, anchor = 1, cx = 130f, cy = 258f, w = 128f, h = 64f, scoreFeature = 4, score = 0.6f)

        val boxes = YoloDetectionPipeline.decode(out, 100, 50, 6.4, 0, 160, params)
        out.release()

        assertEquals(1, boxes.size)
    }

    @Test
    fun keepsBestClassId() {
        // RAW with 6 classes (d6-1..d6-6): class 4 (= value 5) outscores class 1 on the same anchor.
        val out = Mat(10, 20, opencv_core.CV_32F, Scalar(0.0))
        putBox(out, anchor = 0, cx = 128f, cy = 256f, w = 128f, h = 64f, scoreFeature = 4 + 1, score = 0.4f)
        putBox(out, anchor = 0, cx = 128f, cy = 256f, w = 128f, h = 64f, scoreFeature = 4 + 4, score = 0.8f)

        val dets = YoloDetectionPipeline.decode(out, 100, 50, 6.4, 0, 160, params)
        out.release()

        assertEquals(1, dets.size)
        assertEquals(4, dets[0].classId)
        assertEquals(0.8f, dets[0].score, 1e-6f)
    }

    private fun putBox(
        mat: Mat, anchor: Int, cx: Float, cy: Float, w: Float, h: Float, scoreFeature: Int, score: Float,
    ) {
        val idx = mat.createIndexer<FloatIndexer>(true)
        idx.put(0L, anchor.toLong(), cx)
        idx.put(1L, anchor.toLong(), cy)
        idx.put(2L, anchor.toLong(), w)
        idx.put(3L, anchor.toLong(), h)
        idx.put(scoreFeature.toLong(), anchor.toLong(), score)
        idx.release()
    }

    private fun assertBox(box: BoundingBox, left: Float, top: Float, right: Float, bottom: Float) {
        assertTrue("box=$box", closeTo(box.left, left) && closeTo(box.top, top) &&
            closeTo(box.right, right) && closeTo(box.bottom, bottom))
    }

    private fun closeTo(a: Float, b: Float) = kotlin.math.abs(a - b) < 1e-3f

    private companion object {
        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            Loader.load(opencv_core::class.java)
        }
    }
}
