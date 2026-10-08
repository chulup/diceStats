package xyz.chulup.dicestats.recognition.internal

import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.indexer.FloatIndexer
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.global.opencv_imgcodecs
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.opencv_core.Mat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import xyz.chulup.dicestats.recognition.internal.ValueClassifierPipeline.Padding
import xyz.chulup.dicestats.recognition.internal.ValueClassifierPipeline.Square
import java.io.File

class ValueClassifierPipelineTest {

    @Test
    fun squareIsCenteredAndScaledOnTheLongerEdge() {
        // 100×60 box centered at (150, 130): side 130.
        assertEquals(Square(85, 65, 130), ValueClassifierPipeline.squareAround(100f, 100f, 200f, 160f))
    }

    @Test
    fun clipKeepsTheInsideAndPadsTheRest() {
        val (inside, pad) = Square(-10, 20, 50).clipTo(width = 30, height = 60)
        assertArrayEquals(intArrayOf(0, 20, 30, 60), inside)
        assertEquals(Padding(left = 10, top = 0, right = 10, bottom = 10), pad)
    }

    @Test
    fun softmaxesLogitsButKeepsProbabilities() {
        assertArrayEquals(floatArrayOf(0.2f, 0.3f, 0.5f), ValueClassifierPipeline.probabilities(row(0.2f, 0.3f, 0.5f)), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), ValueClassifierPipeline.probabilities(row(2f, 2f)), 1e-6f)
    }

    private fun row(vararg v: Float) = Mat(1, v.size, opencv_core.CV_32F).also { m ->
        val idx = m.createIndexer<FloatIndexer>()
        v.forEachIndexed { i, x -> idx.put(0L, i.toLong(), x) }
        idx.release()
    }

    /** Same probabilities as the Python/OpenCV reference on a test-split crop (needs the local model + data). */
    @Test
    fun matchesPythonReference() {
        val model = File("/ai/runs/dice-two-stage").listFiles()
            ?.firstOrNull { it.name.startsWith("yolo26s-cls-") && !it.name.endsWith("-test") }
            ?.resolve("weights/best.onnx")
        val image = File("/ai/data/dice/cls/v3/test/5/photo-h495184_roll_1782665756944_b0.jpg")
        assumeTrue(model?.exists() == true && image.exists())
        val bgr = opencv_imgcodecs.imread(image.path)
        val rgba = Mat()
        opencv_imgproc.cvtColor(bgr, rgba, opencv_imgproc.COLOR_BGR2RGBA)
        val net = opencv_dnn.readNetFromONNX(model!!.path)
        val probs = ValueClassifierPipeline.classify(rgba, Padding(), net)
        assertArrayEquals(floatArrayOf(0.0001f, 0.0002f, 0.0002f, 0.0027f, 0.9965f, 0.0004f), probs, 0.002f)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNatives() {
            Loader.load(opencv_core::class.java)
        }
    }
}
