package xyz.chulup.dicestats.recognition

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full recognition pass: locate each die ([DieDetector]) and read its value — from the detector
 * itself when it is a [ValueReadingDieDetector] (YOLO), else by counting pips ([PipCounter],
 * OpenCV) — DEVPLAN.md steps 2 + 3 — and flag dice too blurry to read ([BlurDetector]).
 *
 * With three or more [voters], a taken photo is recognized by all of them and the result is their
 * 2-of-3 [Consensus] ([recognizeVoted]); [detector] alone still serves quick re-detections.
 *
 * [shadows] (one-stage YOLO) and [twoStage] (detector + value classifier) are compared against
 * [detector] on real photos: they never affect what the user sees, only [runShadows] runs them,
 * and [classifiers] read the dice the user confirmed ([classifyKnown]) — see `ModelEvalLog`.
 */
class DieRecognizer(
    private val detector: DieDetector = ClassicalDieDetector(),
    private val pipCounter: PipCounter = PipCounter(),
    private val blurDetector: BlurDetector? = BlurDetector(),
    private val shadows: List<YoloDieDetector> = emptyList(),
    private val twoStage: List<TwoStageRecognizer> = emptyList(),
    private val classifiers: List<ValueClassifier> = emptyList(),
    private val voters: List<DicePipeline> = emptyList(),
) {
    /**
     * A recognition plus the timed model pass behind it (null for the classical detector); for a
     * voted one, [run] is the consensus and [votes] the voters' own runs.
     */
    data class Recognition(val dice: List<DetectedDie>, val run: ModelRun?, val votes: List<ModelRun> = emptyList())

    /** One classifier's readings of the confirmed dice, in their order (null = no crop/reading). */
    data class KnownBoxRun(
        val model: String,
        val readings: List<ValueClassifier.Reading?>,
        val classifyMs: Double,
    )

    /** Known-box classification of one photo: the shared crop time plus each classifier's run. */
    data class KnownBoxes(val cropMs: Double, val runs: List<KnownBoxRun>)

    val hasShadows: Boolean get() = shadows.isNotEmpty() || twoStage.isNotEmpty()

    suspend fun recognize(bitmap: Bitmap): List<DetectedDie> = recognizeTimed(bitmap).dice

    suspend fun recognizeTimed(bitmap: Bitmap): Recognition = withContext(Dispatchers.Default) {
        // Favor recall: surface every proposed region, with its value when
        // readable (null otherwise). The user prunes false positives on the
        // confirm screen rather than us dropping them automatically.
        val run = (detector as? YoloDieDetector)?.detectTimed(bitmap)
        val dice = run?.dice
            ?: (detector as? ValueReadingDieDetector)?.detectDice(bitmap)
            ?: detector.detect(bitmap).map { DetectedDie(value = null, boundingBox = it) }
        Recognition(dice.map { die -> finish(bitmap, die) }, run)
    }

    /**
     * Recognizes a taken photo by 2-of-3 vote of the [voters] (one after another); falls back to
     * [recognizeTimed] when fewer than 3 voters are configured or fewer than 2 produce a result.
     * Dice without a 2-vote value come back with a null value (the user reads them).
     */
    suspend fun recognizeVoted(bitmap: Bitmap, photoPath: String): Recognition {
        if (voters.size < MIN_VOTERS) return recognizeTimed(bitmap)
        val runs = voters.mapNotNull { v -> safely(v.name) { v.run(bitmap, photoPath) } }
        if (runs.size < Consensus.MIN_VOTES) return recognizeTimed(bitmap)
        return withContext(Dispatchers.Default) {
            val agreed = Consensus.vote(runs.map { it.dice })
            val dice = agreed.map { blurOf(bitmap, DetectedDie(
                        value = it.value,
                        boundingBox = it.box,
                        score = it.score,
                        votes = it.votes,
                        valueVotes = it.valueVotes,
                    )) }
            val run = ModelRun(
                model = CONSENSUS,
                dice = dice,
                runIndex = runs.minOf { it.runIndex },
                loadMs = runs.mapNotNull { it.loadMs }.takeIf { it.isNotEmpty() }?.sum(),
                preprocessMs = runs.sumOf { it.preprocessMs },
                inferenceMs = runs.sumOf { it.inferenceMs },
                decodeMs = runs.sumOf { it.decodeMs },
                thermalStatus = runs.last().thermalStatus,
                cropMs = runs.sumOf { it.cropMs },
                classifyMs = runs.sumOf { it.classifyMs },
            )
            Recognition(dice, run, runs)
        }
    }

    /**
     * Runs every shadow model on [bitmap] (two-stage ones crop from the full-res [photoPath]),
     * one after another so timings don't overlap. A failing shadow (e.g. out of memory) is
     * logged and skipped: comparison runs must never break recognition.
     */
    suspend fun runShadows(bitmap: Bitmap, photoPath: String): List<ModelRun> =
        shadows.mapNotNull { safely(it.modelName) { it.detectTimed(bitmap) } } +
            twoStage.mapNotNull { safely(it.name) { it.run(bitmap, photoPath) } }

    private suspend fun <T> safely(name: String, block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "shadow $name failed", t)
            null
        }

    /** Crops [boxes] from the full-res [photoPath] once and reads them with every classifier. */
    suspend fun classifyKnown(photoPath: String, boxes: List<BoundingBox>): KnownBoxes? {
        if (classifiers.isEmpty() || boxes.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            val crops = DieCropper(photoPath).use { cropper -> boxes.map { cropper.crop(it) } }
            val cropMs = (System.nanoTime() - t0) / 1e6
            try {
                KnownBoxes(cropMs, classifiers.map { cls ->
                    val t1 = System.nanoTime()
                    val readings = crops.map { crop -> crop?.let { cls.classify(it) } }
                    KnownBoxRun(cls.assetName, readings, (System.nanoTime() - t1) / 1e6)
                })
            } finally {
                crops.forEach { it?.bitmap?.recycle() }
            }
        }
    }

    private fun finish(bitmap: Bitmap, die: DetectedDie): DetectedDie {
        val read = if (die.value != null) die else die.copy(value = pipCounter.count(bitmap, die.boundingBox))
        return blurOf(bitmap, read)
    }

    private fun blurOf(bitmap: Bitmap, die: DetectedDie): DetectedDie {
        val blur = blurDetector?.probability(bitmap, die.boundingBox) ?: return die
        return die.copy(blurProbability = blur, blurry = blurDetector.isBlurry(blur))
    }

    companion object {
        /** [ModelRun.model] of the voted result. */
        const val CONSENSUS = "consensus-2of3"
        private const val MIN_VOTERS = 3
    }
}

private const val TAG = "DieRecognizer"
