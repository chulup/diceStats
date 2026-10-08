package xyz.chulup.dicestats.feature.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.ConfirmedDie
import xyz.chulup.dicestats.data.eval.ConfirmSession
import xyz.chulup.dicestats.data.eval.ConfirmedBox
import xyz.chulup.dicestats.data.eval.DieOrigin
import xyz.chulup.dicestats.data.eval.ModelEvalLog
import xyz.chulup.dicestats.data.photo.sensorsFile
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.data.db.DieEntity
import xyz.chulup.dicestats.recognition.BoundingBox
import xyz.chulup.dicestats.recognition.DieColorAnalyzer
import xyz.chulup.dicestats.recognition.DieColorSignature
import xyz.chulup.dicestats.recognition.DieIdentifier
import xyz.chulup.dicestats.recognition.DieRecognizer
import xyz.chulup.dicestats.recognition.ModelRun
import xyz.chulup.dicestats.recognition.mapOrientedRegionToRaw
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** One detected die on the confirm screen: editable value + chosen registered die. */
data class DieAssignment(
    val boundingBox: BoundingBox,
    val value: Int,
    val recognizedValue: Int?,
    val dieId: Long?,
    /** Confidence of an auto-guessed identity (null when none / user-assigned). */
    val dieIdConfidence: Float? = null,
    /** Colour fingerprint of this crop, carried through to persist + learn. */
    val signature: DieColorSignature? = null,
    /** True once the user has set the value (used to resolve an unread "?" die). */
    val edited: Boolean = false,
    /** Recognition judged the die too blurry to read reliably ([xyz.chulup.dicestats.recognition.BlurDetector]). */
    val blurry: Boolean = false,
    /** How this die was found — logged with the model comparison ([ModelEvalLog]). */
    val origin: DieOrigin = DieOrigin.DETECTED,
    /** The user dragged this box (logged). */
    val moved: Boolean = false,
) {
    /** Whether a definite value is established (auto-recognized or user-set). */
    val hasValue: Boolean get() = recognizedValue != null || edited
}

/** The type of the registered die this assignment points at, or null if unassigned/unknown. */
private fun DieAssignment.dieTypeIn(registered: List<DieEntity>): DieType? =
    dieId?.let { id -> registered.firstOrNull { it.id == id } }?.let { DieType.fromFaces(it.faces) }

/**
 * The die that follows [current] in [order], wrapping past the end — so tapping boxes
 * in turn walks down the palette. Dice already at their per-roll capacity (per
 * [assignedCounts]) are skipped; null when [order] is empty or every die is full.
 * Falls back to the first entry when [current] isn't in [order] (`indexOfFirst` -> -1,
 * so -1+1 == 0).
 */
internal fun nextActiveDie(
    order: List<DieEntity>,
    current: Long,
    assignedCounts: Map<Long, Int> = emptyMap(),
): Long? {
    if (order.isEmpty()) return null
    val cur = order.indexOfFirst { it.id == current }
    for (step in 1..order.size) {
        val candidate = order[(cur + step) % order.size]
        if ((assignedCounts[candidate.id] ?: 0) < candidate.count) return candidate.id
    }
    return null
}

/**
 * The palette die to highlight after a box was identified as [justAssigned]: a pool
 * with remaining per-roll capacity keeps the highlight (tap-tap-tap fills the pool),
 * otherwise the highlight advances to the next non-full die.
 */
internal fun activeDieAfterAssignment(
    order: List<DieEntity>,
    justAssigned: Long,
    assignedCounts: Map<Long, Int>,
): Long? {
    val die = order.firstOrNull { it.id == justAssigned }
    if (die != null && (assignedCounts[die.id] ?: 0) < die.count) return justAssigned
    return nextActiveDie(order, justAssigned, assignedCounts)
}

/**
 * Unassigns the lowest-confidence auto-guesses that would put a die over its per-roll
 * capacity, so recognition never proposes more boxes on a pool than it has dice. (The
 * user can still over-assign manually; [DetectionUiState.Ready.canSave] blocks that.)
 */
internal fun capAutoAssignments(
    assignments: List<DieAssignment>,
    registered: List<DieEntity>,
): List<DieAssignment> {
    val capacity = registered.associate { it.id to it.count }
    val drop = mutableSetOf<Int>()
    assignments.withIndex()
        .filter { it.value.dieId != null }
        .groupBy { it.value.dieId!! }
        .forEach { (dieId, entries) ->
            val cap = capacity[dieId] ?: return@forEach
            if (entries.size > cap) {
                entries.sortedByDescending { it.value.dieIdConfidence ?: 0f }
                    .drop(cap)
                    .forEach { drop += it.index }
            }
        }
    if (drop.isEmpty()) return assignments
    return assignments.mapIndexed { i, a ->
        if (i in drop) a.copy(dieId = null, dieIdConfidence = null) else a
    }
}

private const val TAG = "DetectionViewModel"

/** Compact `[l,t,r,b]` rendering of a normalized box for diagnostic logs. */
private fun BoundingBox.log() = "[%.3f,%.3f,%.3f,%.3f]".format(left, top, right, bottom)

// --- Long-press "add a missed die" geometry (pure; unit-tested in DetectionLogicTest) ---

/**
 * Factor applied per axis to the median detected-die size to size the re-detect window
 * (≈ this many dice across): wide enough to contain the tapped die with margin, tight
 * enough not to drag in a crowd of neighbours.
 */
private const val WINDOW_DIE_FACTOR = 1.5f

/** Half-window as a fraction of image *width* when there are no dice yet to size against. */
private const val WINDOW_FALLBACK_HALF = 0.10f

/** A newly detected box overlapping an existing one by more than this IoU is a duplicate. */
internal const val ADD_DIE_DEDUP_IOU = 0.3f

/**
 * A detection smaller than this fraction of the median existing die's area is a pip/speck,
 * not a die, and is rejected — a tight native-res crop makes the classical detector emit a
 * die's own pips as separate boxes, and those must never be picked over the die itself.
 */
internal const val MIN_DIE_AREA_FRACTION = 0.30f

internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val s = values.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
}

internal fun boxIou(a: BoundingBox, b: BoundingBox): Float {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0f
    val inter = (right - left) * (bottom - top)
    return inter / (a.width * a.height + b.width * b.height - inter)
}

/** A centered [halfW]×[halfH] (normalized) box around ([cx],[cy]), clipped to the image. */
private fun centeredBox(cx: Float, cy: Float, halfW: Float, halfH: Float) = BoundingBox(
    left = (cx - halfW).coerceIn(0f, 1f),
    top = (cy - halfH).coerceIn(0f, 1f),
    right = (cx + halfW).coerceIn(0f, 1f),
    bottom = (cy + halfH).coerceIn(0f, 1f),
)

/**
 * The region to re-run detection on for a long-press at normalized ([cx],[cy]). Sized to
 * a few dice across from the median of already-detected [dice]; when none exist yet, a
 * fixed fraction of the frame kept pixel-square via [aspectRatio] (= width / height).
 */
internal fun detectionWindowAround(
    cx: Float,
    cy: Float,
    dice: List<BoundingBox>,
    aspectRatio: Float,
): BoundingBox {
    val (halfW, halfH) = if (dice.isNotEmpty()) {
        median(dice.map { it.width }) * WINDOW_DIE_FACTOR to
            median(dice.map { it.height }) * WINDOW_DIE_FACTOR
    } else {
        WINDOW_FALLBACK_HALF to WINDOW_FALLBACK_HALF * aspectRatio
    }
    return centeredBox(cx, cy, halfW.coerceIn(0.02f, 0.5f), halfH.coerceIn(0.02f, 0.5f))
}

/** A placeholder box (one median die) centered on the tap, for when detection finds nothing. */
internal fun placeholderBoxAround(
    cx: Float,
    cy: Float,
    dice: List<BoundingBox>,
    aspectRatio: Float,
): BoundingBox {
    val (halfW, halfH) = if (dice.isNotEmpty()) {
        median(dice.map { it.width }) / 2f to median(dice.map { it.height }) / 2f
    } else {
        WINDOW_FALLBACK_HALF / 2f to WINDOW_FALLBACK_HALF / 2f * aspectRatio
    }
    return centeredBox(cx, cy, halfW.coerceIn(0.01f, 0.5f), halfH.coerceIn(0.01f, 0.5f))
}

/**
 * Shifts [box] by ([dx],[dy]) in normalized coords, clamped so the whole box stays within
 * the image — the box is translated (size preserved), not clipped, so dragging past an edge
 * simply parks it against that edge.
 */
internal fun translateBoxClamped(box: BoundingBox, dx: Float, dy: Float): BoundingBox {
    val nx = (box.left + dx).coerceIn(0f, 1f - box.width)
    val ny = (box.top + dy).coerceIn(0f, 1f - box.height)
    return BoundingBox(nx, ny, nx + box.width, ny + box.height)
}

/** Maps a box in [window]-local normalized coords back to full-image normalized coords. */
internal fun mapBoxFromWindow(box: BoundingBox, window: BoundingBox) = BoundingBox(
    left = window.left + box.left * window.width,
    top = window.top + box.top * window.height,
    right = window.left + box.right * window.width,
    bottom = window.top + box.bottom * window.height,
)

/**
 * Of the newly detected [candidates] (full-image coords, with parallel read [values]), the
 * index of the one to add for a long-press at ([cx],[cy]). In order: reject boxes that
 * duplicate an [existing] die (IoU > [dedupIou]) or are pip-sized (< [MIN_DIE_AREA_FRACTION]
 * of the median existing die — a crop makes the detector emit a die's own pips as boxes);
 * of the survivors prefer those that read a value (a real die over an unread speck); then
 * prefer a box containing the tap; then take the nearest by centre. Null when nothing
 * plausible is left — the caller drops a placeholder instead.
 */
internal fun pickAddedDetection(
    candidates: List<BoundingBox>,
    values: List<Int?>,
    existing: List<BoundingBox>,
    cx: Float,
    cy: Float,
    dedupIou: Float = ADD_DIE_DEDUP_IOU,
): Int? {
    val minArea = if (existing.isNotEmpty()) {
        median(existing.map { it.width }) * median(existing.map { it.height }) * MIN_DIE_AREA_FRACTION
    } else {
        0f
    }
    val fresh = candidates.indices.filter { i ->
        val b = candidates[i]
        b.width * b.height >= minArea && existing.none { boxIou(it, b) > dedupIou }
    }
    if (fresh.isEmpty()) return null
    // A real die reads pips; an unread blob is likely a pip/speck — prefer the former.
    val readable = fresh.filter { values[it] != null }
    val pool = readable.ifEmpty { fresh }
    val containing = pool.filter { cx in candidates[it].left..candidates[it].right && cy in candidates[it].top..candidates[it].bottom }
    return containing.ifEmpty { pool }.minByOrNull { i ->
        val b = candidates[i]
        val dx = (b.left + b.right) / 2f - cx
        val dy = (b.top + b.bottom) / 2f - cy
        dx * dx + dy * dy
    }
}

/**
 * Power-of-two `inSampleSize` that keeps the longest edge at or above [targetMaxEdge]
 * (BitmapFactory halves per step), so a decoded bitmap stays large enough to detect on
 * without wasting memory on full-resolution captures.
 */
internal fun sampleSizeFor(width: Int, height: Int, targetMaxEdge: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= targetMaxEdge) {
        longest /= 2
        sample *= 2
    }
    return sample
}

sealed interface DetectionUiState {
    data object Loading : DetectionUiState

    data class Ready(
        /** The photo currently shown — the capture, or a cropped region the user re-detected. */
        val photoPath: String,
        val aspectRatio: Float,
        val dice: List<DieAssignment>,
        val registeredDice: List<DieEntity>,
        /** Die that the next tapped box will be identified as ("next die from the list"). */
        val activeDieId: Long? = null,
        val saving: Boolean = false,
        val saved: Boolean = false,
        /** True while re-detection on a cropped region is running. */
        val detecting: Boolean = false,
        /** Box just added via long-press, briefly emphasized on the overlay; cleared after a beat. */
        val recentlyAddedIndex: Int? = null,
    ) : DetectionUiState {
        /**
         * Every die must be assigned and have a value that's a real face of it, and no
         * die may carry more boxes than its per-roll capacity — a pool of 3 can't
         * appear 4 times in one photo (DESIGN.md "Die Pools"; fewer is fine).
         */
        val canSave: Boolean
            get() = dice.isNotEmpty() &&
                dice.all { it.dieTypeIn(registeredDice)?.isValidValue(it.value) == true } &&
                assignedCounts.all { (id, n) ->
                    (registeredDice.firstOrNull { it.id == id }?.count ?: 0) >= n
                }

        /** Boxes assigned to each die in this roll, keyed by die id. */
        val assignedCounts: Map<Long, Int>
            get() = dice.mapNotNull { it.dieId }.groupingBy { it }.eachCount()

        /** Dice whose per-roll capacity is exhausted — the palette grays these out. */
        val atCapacityDieIds: Set<Long>
            get() {
                val counts = assignedCounts
                return registeredDice
                    .filter { (counts[it.id] ?: 0) >= it.count }
                    .map { it.id }
                    .toSet()
            }

        /** Registered dice ordered most-recent-first — the tap-to-identify palette. */
        val recentDice: List<DieEntity>
            get() = registeredDice.sortedByDescending { it.createdAt }
    }

    data class Error(val message: String) : DetectionUiState
}

/**
 * Recognizes dice in the captured photo (detection + pip counting), lets the user
 * confirm/correct each value and assign it to a registered die, then persists the
 * roll (DEVPLAN.md step 4).
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DiceRepository,
    private val recognizer: DieRecognizer,
    private val evalLog: ModelEvalLog,
) : ViewModel() {

    /** The working photo — starts as the capture, becomes the crop when re-detecting. */
    private var currentPhotoPath: String =
        savedStateHandle.get<String>(ARG_PHOTO_PATH) ?: error("photoPath argument required")

    /**
     * The model comparison for the current photo: the shown model's pass, plus the shadow
     * models' passes still running in the background. Logged on [save].
     */
    private class EvalCapture(
        val width: Int,
        val height: Int,
        val cropped: Boolean,
        val shown: ModelRun?,
        /** The voters' own runs behind a 2-of-3 consensus ([shown]); empty otherwise. */
        val votes: List<ModelRun>,
        val shadows: Deferred<List<ModelRun>>?,
    )

    @Volatile private var eval: EvalCapture? = null

    /** Confirm-screen effort for the current photo, logged on save ([ConfirmSession]). */
    private var openedAt = 0L
    private var readyAt = 0L
    private var removedCount = 0
    private var addedCount = 0
    private var valueEditCount = 0
    private var redetectCount = 0

    private val colorAnalyzer = DieColorAnalyzer()
    private val identifier = DieIdentifier()

    private val registeredDice = repository.dice
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _uiState = MutableStateFlow<DetectionUiState>(DetectionUiState.Loading)
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    /** Flips true once the photo has been filed for later analysis (drives navigation). */
    private val _reported = MutableStateFlow(false)
    val reported: StateFlow<Boolean> = _reported.asStateFlow()

    init {
        recognize()
        // Keep the die picker in sync as dice get registered, defaulting the active
        // (next-to-assign) die to the most recent one when none is chosen yet.
        viewModelScope.launch {
            registeredDice.collect { dice ->
                _uiState.update { state ->
                    if (state !is DetectionUiState.Ready) return@update state
                    val active = state.activeDieId ?: dice.maxByOrNull { it.createdAt }?.id
                    state.copy(registeredDice = dice, activeDieId = active)
                }
            }
        }
    }

    private fun recognize() {
        viewModelScope.launch {
            _uiState.value = DetectionUiState.Loading
            openedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                val bitmap = withContext(Dispatchers.Default) { decodeOriented(currentPhotoPath) }
                    ?: error("Could not decode photo")
                val (assignments, recognition) = buildAssignments(bitmap, registeredDice.value, currentPhotoPath)
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                startEval(bitmap, currentPhotoPath, recognition, cropped = false)
                ratio to assignments
            }
            readyAt = SystemClock.elapsedRealtime()
            _uiState.value = result.fold(
                onSuccess = { (ratio, dice) ->
                    val registered = registeredDice.value
                    // Start the palette on the newest die that still has room in this
                    // roll (auto-assignment may have filled a pool already).
                    val counts = dice.mapNotNull { it.dieId }.groupingBy { it }.eachCount()
                    val active = registered
                        .sortedByDescending { it.createdAt }
                        .firstOrNull { (counts[it.id] ?: 0) < it.count }
                    DetectionUiState.Ready(
                        photoPath = currentPhotoPath,
                        aspectRatio = ratio,
                        dice = dice,
                        registeredDice = registered,
                        activeDieId = active?.id,
                    )
                },
                onFailure = { DetectionUiState.Error(it.message ?: "Detection failed") },
            )
        }
    }

    /**
     * Re-detects on the user-selected [region] (normalized, in display coordinates):
     * crops the current photo to that region at native resolution, makes the crop the
     * new working photo, and runs detection afresh — replacing the prior photo and
     * results so the user can drill into dice the full-frame pass missed.
     */
    fun detectInRegion(region: BoundingBox) {
        val state = _uiState.value as? DetectionUiState.Ready ?: return
        if (state.detecting) return
        val previousPath = currentPhotoPath
        redetectCount++
        _uiState.update { (it as DetectionUiState.Ready).copy(detecting = true) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val crop = decodeRegion(previousPath, region) ?: return@withContext null
                // Stored first: the two-stage voters crop from the file.
                val newPath = repository.storePhoto(crop)
                val (dice, recognition) = buildAssignments(crop, registeredDice.value, newPath)
                val ratio = crop.width.toFloat() / crop.height.toFloat()
                startEval(crop, newPath, recognition, cropped = true)
                Triple(newPath, ratio, dice)
            }
            if (outcome == null) {
                _uiState.update { if (it is DetectionUiState.Ready) it.copy(detecting = false) else it }
                return@launch
            }
            val (newPath, ratio, dice) = outcome
            currentPhotoPath = newPath
            withContext(Dispatchers.IO) {
                runCatching { sensorsFile(previousPath).renameTo(sensorsFile(newPath)) }
                runCatching { File(previousPath).delete() }
            }
            _uiState.update { current ->
                if (current !is DetectionUiState.Ready) return@update current
                current.copy(
                    photoPath = newPath,
                    aspectRatio = ratio,
                    dice = dice,
                    detecting = false,
                )
            }
        }
    }

    /**
     * Long-press recovery for a die the detector missed: re-runs detection on a small
     * native-resolution window around the tapped point ([nx],[ny] normalized, display
     * coords) and merges the found die back into the full-frame results, mapped to image
     * coordinates. If detection finds nothing there either, it drops an amber placeholder
     * box on the tap for the user to size and set a value on — so a long-press always adds
     * a die. Reuses [decodeRegion]'s native-res crop, which is what lets a tighter window
     * find a die the downscaled full frame missed.
     */
    fun addDieAt(nx: Float, ny: Float) {
        val state = _uiState.value as? DetectionUiState.Ready ?: return
        if (state.detecting) return
        val path = currentPhotoPath
        val existing = state.dice.map { it.boundingBox }
        val window = detectionWindowAround(nx, ny, existing, state.aspectRatio)
        Log.i(
            TAG,
            "addDieAt: tap=(%.3f,%.3f) window=%s existing=%d".format(nx, ny, window.log(), existing.size),
        )
        addedCount++
        _uiState.update { (it as DetectionUiState.Ready).copy(detecting = true) }
        viewModelScope.launch {
            val added = withContext(Dispatchers.Default) {
                val crop = decodeRegion(path, window)
                if (crop == null) {
                    Log.w(TAG, "addDieAt: crop decode failed for $path -> placeholder")
                } else {
                    try {
                        val detected = recognizer.recognize(crop)
                        val full = detected.map { mapBoxFromWindow(it.boundingBox, window) }
                        Log.i(TAG, "addDieAt: window detections=${detected.size}")
                        full.forEachIndexed { i, b ->
                            Log.i(TAG, "   det[$i] ${b.log()} value=${detected[i].value}")
                        }
                        val pick = pickAddedDetection(full, detected.map { it.value }, existing, nx, ny)
                        if (pick != null) {
                            val det = detected[pick]
                            val signature = colorAnalyzer.signature(crop, det.boundingBox)
                            val guess = signature
                                ?.let { identifier.identify(it, identityCandidatesFor(state.registeredDice)) }
                                ?.takeIf { it.confidence >= DieIdentifier.IDENTITY_CONFIRM_THRESHOLD }
                            Log.i(
                                TAG,
                                "addDieAt: picked det[$pick] ${full[pick].log()} value=${det.value} " +
                                    "dieId=${guess?.dieId} conf=${guess?.confidence}",
                            )
                            return@withContext DieAssignment(
                                boundingBox = full[pick],
                                value = det.value ?: DEFAULT_VALUE,
                                recognizedValue = det.value,
                                dieId = guess?.dieId,
                                dieIdConfidence = guess?.confidence,
                                signature = signature,
                                blurry = det.blurry,
                                origin = DieOrigin.ADDED,
                            )
                        }
                        Log.i(
                            TAG,
                            "addDieAt: no fresh detection (all ${detected.size} were duplicates/empty) -> placeholder",
                        )
                    } finally {
                        crop.recycle()
                    }
                }
                // Detection found nothing new (or the crop failed): a placeholder to fill in.
                DieAssignment(
                    boundingBox = placeholderBoxAround(nx, ny, existing, state.aspectRatio),
                    value = DEFAULT_VALUE,
                    recognizedValue = null,
                    dieId = null,
                    origin = DieOrigin.PLACEHOLDER,
                )
            }
            Log.i(
                TAG,
                "addDieAt: added ${added.boundingBox.log()} value=${added.value} " +
                    "recognized=${added.recognizedValue} placeholder=${added.recognizedValue == null}",
            )
            _uiState.update { current ->
                if (current !is DetectionUiState.Ready) return@update current
                val merged = capAutoAssignments(current.dice + added, current.registeredDice)
                current.copy(dice = merged, detecting = false, recentlyAddedIndex = merged.lastIndex)
            }
            launch {
                delay(ADD_DIE_FLASH_MS)
                _uiState.update {
                    if (it is DetectionUiState.Ready) it.copy(recentlyAddedIndex = null) else it
                }
            }
        }
    }

    /**
     * Builds confirm-screen assignments from a freshly detected [bitmap] (the photo at [photoPath],
     * downscaled), with the recognition behind them — a 2-of-3 vote when configured.
     */
    private suspend fun buildAssignments(
        bitmap: Bitmap,
        registered: List<DieEntity>,
        photoPath: String,
    ): Pair<List<DieAssignment>, DieRecognizer.Recognition> {
        val recognition = recognizer.recognizeVoted(bitmap, photoPath)
        val detected = recognition.dice
        val candidates = identityCandidatesFor(registered)
        val assignments = detected.map { det ->
            val signature = colorAnalyzer.signature(bitmap, det.boundingBox)
            val guess = signature
                ?.let { identifier.identify(it, candidates) }
                ?.takeIf { it.confidence >= DieIdentifier.IDENTITY_CONFIRM_THRESHOLD }
            DieAssignment(
                boundingBox = det.boundingBox,
                value = det.value ?: DEFAULT_VALUE,
                recognizedValue = det.value,
                dieId = guess?.dieId,
                dieIdConfidence = guess?.confidence,
                signature = signature,
                blurry = det.blurry,
            )
        }
        return capAutoAssignments(assignments, registered) to recognition
    }

    /**
     * Starts the model comparison for a new working photo: runs the shadow models on [bitmap]
     * in the background (then recycles it) while the user confirms the dice.
     */
    private fun startEval(bitmap: Bitmap, photoPath: String, recognition: DieRecognizer.Recognition, cropped: Boolean) {
        eval?.shadows?.cancel()
        val (width, height) = bitmap.width to bitmap.height
        val shadows = if (recognizer.hasShadows) {
            viewModelScope.async(Dispatchers.Default) {
                try {
                    recognizer.runShadows(bitmap, photoPath)
                } finally {
                    bitmap.recycle()
                }
            }
        } else {
            bitmap.recycle()
            null
        }
        eval = EvalCapture(width, height, cropped, recognition.run, recognition.votes, shadows)
    }

    /** Logs every model's pass on the saved photo against the confirmed dice; never fails the save. */
    private suspend fun logEval(dice: List<DieAssignment>) {
        val capture = eval ?: return
        runCatching {
            val shadows = capture.shadows?.let { withTimeoutOrNull(SHADOW_WAIT_MS) { it.await() } }.orEmpty()
            val runs = listOfNotNull(capture.shown) + capture.votes + shadows
            if (runs.isEmpty()) return
            val known = recognizer.classifyKnown(currentPhotoPath, dice.map { it.boundingBox })
            evalLog.record(
                photo = currentPhotoPath,
                width = capture.width,
                height = capture.height,
                cropped = capture.cropped,
                shown = capture.shown?.model,
                confirmed = dice.map {
                    ConfirmedBox(it.boundingBox, it.value, it.recognizedValue, it.origin, it.blurry, it.edited, it.moved)
                },
                runs = runs,
                sensors = readSensors(),
                knownBoxes = known,
                session = ConfirmSession(
                    latencyMs = readyAt - openedAt,
                    confirmMs = SystemClock.elapsedRealtime() - readyAt,
                    removed = removedCount,
                    added = addedCount,
                    valueEdits = valueEditCount,
                    redetects = redetectCount,
                ),
            )
        }.onFailure { Log.w(TAG, "model eval log failed", it) }
    }

    /**
     * Identity candidates for matching a crop's colour: pools are candidates only inside a
     * game that opted into them (DESIGN.md "Die Pools"); individual dice always are.
     */
    private suspend fun identityCandidatesFor(registered: List<DieEntity>): List<DieIdentifier.Candidate> {
        val poolsEnabled = repository.activeGameNow()?.usesDicePools == true
        return identityCandidates(registered.filter { poolsEnabled || !it.isPool })
    }

    private fun identityCandidates(dice: List<DieEntity>): List<DieIdentifier.Candidate> =
        dice.mapNotNull { d ->
            d.colorSignature
                ?.let { DieColorSignature.decode(it) }
                ?.let { DieIdentifier.Candidate(d.id, it) }
        }

    /** Sets a die's value, clamped to the assigned die's possible range (d6 until assigned). */
    fun setValue(index: Int, value: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            val die = state.dice.getOrNull(index) ?: return@update state
            val type = die.dieTypeIn(state.registeredDice) ?: DieType.DEFAULT
            val clamped = value.coerceIn(type.minValue, type.maxValue)
            if (clamped != die.value) valueEditCount++
            state.copy(
                dice = state.dice.mapIndexed { i, d ->
                    if (i == index) d.copy(value = clamped, edited = true) else d
                },
            )
        }
    }

    /** Drops a detected die the user judges to be a false positive. */
    fun removeDie(index: Int) {
        (_uiState.value as? DetectionUiState.Ready)?.dice?.getOrNull(index)?.let { die ->
            Log.i(
                TAG,
                "removeDie: index=$index ${die.boundingBox.log()} value=${die.value} " +
                    "recognized=${die.recognizedValue} dieId=${die.dieId}",
            )
        }
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            if (index in state.dice.indices) removedCount++
            state.copy(dice = state.dice.filterIndexed { i, _ -> i != index })
        }
    }

    /**
     * Drags a die's box by ([dx],[dy]) (normalized deltas) so the user can center it over a
     * die the detector boxed loosely — or place a long-press placeholder precisely. Clamped
     * to the image; size is preserved.
     */
    fun moveDie(index: Int, dx: Float, dy: Float) =
        updateDie(index) { it.copy(boundingBox = translateBoxClamped(it.boundingBox, dx, dy), moved = true) }

    fun assignDie(index: Int, dieId: Long) = updateDie(index) { it.copy(dieId = dieId) }

    /** Picks which die the next tapped box will be identified as. */
    fun selectActiveDie(dieId: Long) {
        _uiState.update { if (it is DetectionUiState.Ready) it.copy(activeDieId = dieId) else it }
    }

    /**
     * Identifies the tapped die as the active die from the palette. The active
     * selection stays on a pool until its per-roll capacity is used, then advances
     * to the next non-full die — so tapping boxes in order walks down the palette,
     * lingering on pools (DESIGN.md "Die Pools").
     */
    fun identifyAsActive(index: Int) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            val active = state.activeDieId ?: return@update state
            val assigned = state.dice.mapIndexed { i, die ->
                if (i == index) die.copy(dieId = active) else die
            }
            val counts = assigned.mapNotNull { it.dieId }.groupingBy { it }.eachCount()
            state.copy(
                dice = assigned,
                activeDieId = activeDieAfterAssignment(state.recentDice, active, counts),
            )
        }
    }

    fun registerAndAssign(index: Int, name: String, faces: Int, count: Int = 1) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed, faces, count)
            updateDie(index) { it.copy(dieId = id) }
        }
    }

    /** Registers a new die (or pool, when [count] > 1) from the palette and makes it active. */
    fun registerAndSetActive(name: String, faces: Int, count: Int = 1) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed, faces, count)
            _uiState.update { if (it is DetectionUiState.Ready) it.copy(activeDieId = id) else it }
        }
    }

    fun save() {
        val state = _uiState.value as? DetectionUiState.Ready ?: return
        if (!state.canSave || state.saving) return
        _uiState.update { (it as DetectionUiState.Ready).copy(saving = true) }
        viewModelScope.launch {
            val confirmed = state.dice.map { die ->
                ConfirmedDie(
                    dieId = die.dieId!!,
                    value = die.value,
                    confidence = if (die.recognizedValue == die.value) 1f else 0f,
                    boundingBox = die.boundingBox,
                    wasCorrected = die.recognizedValue != die.value,
                    colorSignature = die.signature,
                )
            }
            repository.saveRoll(currentPhotoPath, System.currentTimeMillis(), confirmed)
            logEval(state.dice)
            _uiState.update { (it as DetectionUiState.Ready).copy(saving = false, saved = true) }
        }
    }

    /**
     * Files the current photo (with the recognizer's output as metadata) for later
     * analysis, then discards the original capture and signals navigation back to
     * the camera. A development aid for collecting badly-recognized photos.
     */
    fun reportUnrecognized() {
        if (_reported.value) return
        val metadata = buildReportMetadata()
        viewModelScope.launch {
            repository.reportUnrecognized(currentPhotoPath, metadata)
            discardPhoto()
            _reported.value = true
        }
    }

    private fun buildReportMetadata(): String {
        val json = JSONObject()
        json.put("reportedAt", System.currentTimeMillis())
        json.put("photo", File(currentPhotoPath).name)
        readSensors()?.let { json.put("sensors", it) }
        when (val state = _uiState.value) {
            is DetectionUiState.Ready -> {
                json.put("aspectRatio", state.aspectRatio.toDouble())
                val dice = JSONArray()
                state.dice.forEach { die ->
                    val box = die.boundingBox
                    val entry = JSONObject()
                    entry.put(
                        "box",
                        JSONArray(listOf(box.left, box.top, box.right, box.bottom)),
                    )
                    entry.put("recognizedValue", die.recognizedValue ?: JSONObject.NULL)
                    entry.put("value", die.value)
                    entry.put("dieIdConfidence", die.dieIdConfidence ?: JSONObject.NULL)
                    dice.put(entry)
                }
                json.put("dice", dice)
            }

            is DetectionUiState.Error -> json.put("error", state.message)
            DetectionUiState.Loading -> json.put("state", "loading")
        }
        return json.toString()
    }

    /** The capture's phone-sensor snapshot (`.sensors.json` sidecar), if one was recorded. */
    private fun readSensors(): JSONObject? =
        runCatching { JSONObject(sensorsFile(currentPhotoPath).readText()) }.getOrNull()

    /** Deletes the captured photo when the user retakes or backs out without saving. */
    fun discardPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { File(currentPhotoPath).delete() }
            runCatching { sensorsFile(currentPhotoPath).delete() }
        }
    }

    private fun updateDie(index: Int, transform: (DieAssignment) -> DieAssignment) {
        _uiState.update { state ->
            if (state !is DetectionUiState.Ready) return@update state
            state.copy(dice = state.dice.mapIndexed { i, die -> if (i == index) transform(die) else die })
        }
    }

    private fun decodeOriented(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, TARGET_MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null

        val rotation = exifRotationDegrees(path)
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    /**
     * Decodes [region] (normalized, in display coordinates) from [path] at native
     * resolution via [BitmapRegionDecoder], returning the crop oriented for display.
     * Decoding the sub-rectangle from the original file (rather than cropping the
     * downscaled bitmap) is what recovers detail for small dice.
     */
    private fun decodeRegion(path: String, region: BoundingBox): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null

        @Suppress("DEPRECATION")
        val decoder = file.inputStream().use { BitmapRegionDecoder.newInstance(it, false) }
            ?: return null
        try {
            val rotation = exifRotationDegrees(path)
            val raw = mapOrientedRegionToRaw(region, decoder.width, decoder.height, rotation)
            val rect = Rect(raw.left, raw.top, raw.right, raw.bottom)
            if (rect.width() <= 0 || rect.height() <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(rect.width(), rect.height(), REGION_TARGET_MAX_EDGE)
            }
            val decoded = decoder.decodeRegion(rect, options) ?: return null

            if (rotation == 0) return decoded
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            return rotated
        } finally {
            decoder.recycle()
        }
    }

    private fun exifRotationDegrees(path: String): Int =
        when (ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    companion object {
        const val ARG_PHOTO_PATH = "photoPath"
        private const val TARGET_MAX_EDGE = 1280
        private const val REGION_TARGET_MAX_EDGE = 1280
        private const val DEFAULT_VALUE = 1
        private const val ADD_DIE_FLASH_MS = 1500L
        private const val SHADOW_WAIT_MS = 15_000L
    }
}
