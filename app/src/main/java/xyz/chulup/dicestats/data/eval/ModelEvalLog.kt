package xyz.chulup.dicestats.data.eval

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieRecognizer
import xyz.chulup.dicestats.recognition.ModelRun
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device model comparison log: for every saved roll, each model's detections and timings on
 * the same photo, scored against the dice the user confirmed. One JSON object per line in
 * `<externalFilesDir>/model-eval/log.jsonl`, next to the roll photos it names (`rolls/`), so
 * both can be pulled for analysis and training (`../training/app_eval_report.py`).
 */
@Singleton
class ModelEvalLog @Inject constructor(@ApplicationContext private val context: Context) {

    private val file: File
        get() = File(File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }, FILE_NAME)

    /**
     * Appends one saved roll. [runs] start with the model whose boxes the user saw ([shown]);
     * [width]×[height] is the bitmap the models ran on; [cropped] when the user re-detected
     * inside a selected region (the photo is then the crop); [sensors] is the capture's phone-sensor
     * snapshot (`SensorRecorder`); [knownBoxes] the value classifiers' readings of the confirmed
     * dice (same order as [confirmed]).
     */
    suspend fun record(
        photo: String,
        width: Int,
        height: Int,
        cropped: Boolean,
        shown: String?,
        confirmed: List<ConfirmedBox>,
        runs: List<ModelRun>,
        sensors: JSONObject? = null,
        knownBoxes: DieRecognizer.KnownBoxes? = null,
        session: ConfirmSession? = null,
    ) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("photo", File(photo).name)
            .put("width", width)
            .put("height", height)
            .put("cropped", cropped)
            .put("shown", shown ?: JSONObject.NULL)
            .put("device", Build.MODEL)
            .put("sensors", sensors ?: JSONObject.NULL)
            .put("confirmed", JSONArray(confirmed.map { it.toJson() }))
            .put("runs", JSONArray(runs.map { run -> run.toJson(scoreRun(run.dice, confirmed)) }))
            .put("knownBoxes", knownBoxes?.toJson(confirmed) ?: JSONObject.NULL)
            .put("session", session?.toJson() ?: JSONObject.NULL)
            .put("models", modelFiles())
        knownBoxes?.runs?.forEach { run ->
            val right = run.readings.zip(confirmed).count { (r, c) -> r?.value == c.value }
            Log.i(
                TAG,
                "%s on %d confirmed dice: %d right, crop %.0f ms, classify %.0f ms".format(
                    run.model, confirmed.size, right, knownBoxes.cropMs, run.classifyMs,
                ),
            )
        }
        runs.forEach { run ->
            val s = scoreRun(run.dice, confirmed)
            Log.i(
                TAG,
                "%s run#%d %.0f ms (infer %.0f): found %d/%d, value ok %d, extra %d".format(
                    run.model, run.runIndex, run.totalMs, run.inferenceMs,
                    s.found, confirmed.size, s.valueCorrect, s.extra,
                ),
            )
        }
        file.appendText(json.toString() + "\n")
    }

    private fun ConfirmedBox.toJson() = JSONObject()
        .put("box", box.toJson())
        .put("value", value)
        .put("recognizedValue", recognizedValue ?: JSONObject.NULL)
        .put("origin", origin.name.lowercase())
        .put("blurry", blurry)
        .put("edited", edited)
        .put("moved", moved)

    private fun ModelRun.toJson(score: RunScore) = JSONObject()
        .put("model", model)
        .put("runIndex", runIndex)
        .put("loadMs", loadMs ?: JSONObject.NULL)
        .put("preprocessMs", preprocessMs)
        .put("inferenceMs", inferenceMs)
        .put("decodeMs", decodeMs)
        .put("cropMs", cropMs)
        .put("classifyMs", classifyMs)
        .put("totalMs", totalMs)
        .put("thermal", thermalStatus)
        .put("found", score.found)
        .put("valueCorrect", score.valueCorrect)
        .put("missed", score.missed)
        .put("extra", score.extra)
        .put(
            "dice",
            JSONArray(
                dice.map { d ->
                    JSONObject()
                        .put("box", d.boundingBox.toJson())
                        .put("value", d.value ?: JSONObject.NULL)
                        .put("score", d.score?.toDouble() ?: JSONObject.NULL)
                        .put("valueScore", d.valueScore?.toDouble() ?: JSONObject.NULL)
                        .put("votes", d.votes ?: JSONObject.NULL)
                        .put("valueVotes", d.valueVotes ?: JSONObject.NULL)
                        .put("blurProb", d.blurProbability?.toDouble() ?: JSONObject.NULL)
                },
            ),
        )

    private fun DieRecognizer.KnownBoxes.toJson(confirmed: List<ConfirmedBox>) = JSONObject()
        .put("cropMs", cropMs)
        .put(
            "runs",
            JSONArray(
                runs.map { run ->
                    JSONObject()
                        .put("model", run.model)
                        .put("classifyMs", run.classifyMs)
                        .put("right", run.readings.zip(confirmed).count { (r, c) -> r?.value == c.value })
                        .put("values", JSONArray(run.readings.map { it?.value ?: JSONObject.NULL }))
                        .put("probs", JSONArray(run.readings.map { it?.probability?.toDouble() ?: JSONObject.NULL }))
                },
            ),
        )

    private fun ConfirmSession.toJson() = JSONObject()
        .put("latencyMs", latencyMs)
        .put("confirmMs", confirmMs)
        .put("removed", removed)
        .put("added", added)
        .put("valueEdits", valueEdits)
        .put("redetects", redetects)

    /** Bundled model assets and their sizes, so each line says which model versions produced it. */
    private fun modelFiles(): JSONObject {
        val json = JSONObject()
        context.assets.list("")?.filter { it.endsWith(".onnx") }?.forEach { name ->
            runCatching { context.assets.openFd(name).use { json.put(name, it.length) } }
                .onFailure { runCatching { json.put(name, context.assets.open(name).use { s -> s.available() }) } }
        }
        return json
    }

    private fun BoundingBox.toJson() = JSONArray(listOf(left, top, right, bottom).map { it.toDouble() })

    private companion object {
        const val DIR_NAME = "model-eval"
        const val FILE_NAME = "log.jsonl"
        const val TAG = "ModelEvalLog"
    }
}
