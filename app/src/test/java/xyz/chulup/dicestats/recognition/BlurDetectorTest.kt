package xyz.chulup.dicestats.recognition

import com.google.gson.Gson
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.opencv_core.Mat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs

/**
 * Parity of [BlurDetector] with the training script `../training/blur_app_model.py`: the fixtures
 * in `resources/blur/` are working-scale margin crops of reviewed dice (binary PPM) with the
 * features and probability Python computed for them, plus the script's `model.json`, which
 * [BlurModel.DEFAULT] must match. Regenerate both with `blur_app_model.py --copy-to ../diceStats`.
 */
class BlurDetectorTest {

    private data class FixturesFile(val fixtures: List<Fixture>)
    private data class Fixture(
        val file: String,
        val die_px: Double,
        val features: Map<String, Double>,
        val probability: Double,
        val blurry_label: Boolean,
    )
    private data class ModelFile(
        val mean: List<Double>,
        val scale: List<Double>,
        val weights: List<Double>,
        val bias: Double,
        val threshold: Double,
    )

    @Test
    fun defaultModelMatchesTrainingExport() {
        val m = Gson().fromJson(resource("blur/model.json").reader(), ModelFile::class.java)
        val d = BlurModel.DEFAULT
        assertEquals(m.mean, d.mean)
        assertEquals(m.scale, d.scale)
        assertEquals(m.weights, d.weights)
        assertEquals(m.bias, d.bias, 0.0)
        assertEquals(m.threshold, d.threshold, 0.0)
    }

    @Test
    fun featuresAndProbabilityMatchPython() {
        val spec = Gson().fromJson(resource("blur/fixtures.json").reader(), FixturesFile::class.java)
        assertTrue(spec.fixtures.isNotEmpty())
        val failures = mutableListOf<String>()
        for (fx in spec.fixtures) {
            val crop = resource("blur/${fx.file}").use { readPpm(it) }
            val f = BlurDetector.features(crop)
            crop.release()
            val got = mapOf("tenengrad" to f.tenengrad, "norm_grad" to f.normGrad, "aniso" to f.aniso)
            for ((name, value) in got) {
                val want = fx.features.getValue(name)
                if (abs(value - want) > REL_TOL * abs(want) + 1e-6) failures += "${fx.file} $name: $value vs $want"
            }
            val p = BlurModel.DEFAULT.probability(f, fx.die_px)
            if (abs(p - fx.probability) > PROB_TOL) failures += "${fx.file} p: $p vs ${fx.probability}"
        }
        assertEquals("Blur parity mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    private fun resource(path: String): InputStream =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "missing test resource $path" }

    /** Binary PPM (P6) → RGB [Mat] (CV_8UC3), the layout the Python fixtures were cut in. */
    private fun readPpm(stream: InputStream): Mat {
        val input = DataInputStream(stream.buffered())
        require(readToken(input) == "P6") { "not a P6 PPM" }
        val width = readToken(input).toInt()
        val height = readToken(input).toInt()
        readToken(input) // maxval 255
        val rgb = ByteArray(width * height * 3)
        input.readFully(rgb)
        val mat = Mat(height, width, opencv_core.CV_8UC3)
        mat.data().put(*rgb)
        return mat
    }

    private fun readToken(input: DataInputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        while (c != -1 && Character.isWhitespace(c)) c = input.read()
        while (c != -1 && !Character.isWhitespace(c)) {
            sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private companion object {
        const val REL_TOL = 1e-3
        const val PROB_TOL = 1e-3

        @JvmStatic
        @BeforeClass
        fun loadOpenCv() {
            Loader.load(opencv_core::class.java)
        }
    }
}
