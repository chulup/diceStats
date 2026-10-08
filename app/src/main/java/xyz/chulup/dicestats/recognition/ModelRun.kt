package xyz.chulup.dicestats.recognition

/**
 * One timed pass of a detection model over a photo, kept for the on-device model comparison
 * (`ModelEvalLog`): what the model found and how long it took.
 *
 * @param model model asset name, e.g. `yolo26m-dice.onnx`.
 * @param runIndex 0 for the model's first pass since the app started — slower (allocations,
 *   caches); drop it when comparing speed.
 * @param loadMs time to read and parse the model, set only on the pass that loaded it.
 * @param thermalStatus `PowerManager.currentThermalStatus` when the pass ran (0 = none, -1 = unknown).
 * @param cropMs two-stage only: cutting the dice out of the full-resolution photo ([DieCropper]).
 * @param classifyMs two-stage only: reading their values ([ValueClassifier]), all dice together.
 */
data class ModelRun(
    val model: String,
    val dice: List<DetectedDie>,
    val runIndex: Int,
    val loadMs: Double?,
    val preprocessMs: Double,
    val inferenceMs: Double,
    val decodeMs: Double,
    val thermalStatus: Int,
    val cropMs: Double = 0.0,
    val classifyMs: Double = 0.0,
) {
    val totalMs: Double get() = preprocessMs + inferenceMs + decodeMs + cropMs + classifyMs
}
