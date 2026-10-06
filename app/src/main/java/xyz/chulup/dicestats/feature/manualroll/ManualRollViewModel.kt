package xyz.chulup.dicestats.feature.manualroll

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.data.ManualDie
import xyz.chulup.dicestats.data.db.DieEntity
import javax.inject.Inject

/** One manually-entered die result within a roll line, in the order it was tapped. */
data class ManualResult(val dieId: Long, val value: Int)

/** One roll being entered — a list of per-die results, e.g. "Red ×3: 2,3,4". */
data class RollLine(val localId: Long, val results: List<ManualResult> = emptyList())

/** A die and the values entered for it in one line, for display ("Red ×3: 2,3,4"). */
data class DisplayGroup(val die: DieEntity, val values: List<Int>)

/**
 * How many more results a [die] may take in [results]: a die/pool holds at most its
 * `count` values in one roll (a single die → 1). Zero means the next value replaces
 * the die's last entry rather than adding another (DESIGN.md "Die Pools").
 */
internal fun capacityRemaining(die: DieEntity, results: List<ManualResult>): Int =
    (die.count - results.count { it.dieId == die.id }).coerceAtLeast(0)

/**
 * Groups a line's [results] by die for display, preserving first-appearance order and
 * each die's tap order. Results whose die is absent from [dice] are dropped (a die can't
 * be shown or its capacity judged without its entity).
 */
internal fun groupForDisplay(results: List<ManualResult>, dice: List<DieEntity>): List<DisplayGroup> {
    val byId = dice.associateBy { it.id }
    val order = mutableListOf<Long>()
    val values = LinkedHashMap<Long, MutableList<Int>>()
    results.forEach { r ->
        if (r.dieId !in byId) return@forEach
        if (r.dieId !in values) order += r.dieId
        values.getOrPut(r.dieId) { mutableListOf() } += r.value
    }
    return order.mapNotNull { id -> byId[id]?.let { DisplayGroup(it, values.getValue(id)) } }
}

/**
 * Adds [value] for [die] to [results]: appends when the die still has capacity, otherwise
 * overwrites the die's most recent value (so tapping again on a full single die just
 * corrects it). Order is preserved.
 */
internal fun appendOrReplace(results: List<ManualResult>, die: DieEntity, value: Int): List<ManualResult> {
    if (capacityRemaining(die, results) > 0) return results + ManualResult(die.id, value)
    val lastIndex = results.indexOfLast { it.dieId == die.id }
    if (lastIndex < 0) return results + ManualResult(die.id, value)
    return results.mapIndexed { i, r -> if (i == lastIndex) r.copy(value = value) else r }
}

data class ManualRollUiState(
    val dice: List<DieEntity> = emptyList(),
    /** The die whose faces the keypad shows and that taps are attributed to. */
    val selectedDieId: Long? = null,
    /** All roll lines being entered; the last is the "new" line. Always non-empty. */
    val lines: List<RollLine> = listOf(RollLine(0L)),
    /** Which line taps are added to / edited. */
    val activeLineIndex: Int = 0,
    /** A result in the active line selected for overwrite by the next tap, or null. */
    val editingResultIndex: Int? = null,
    val saved: Boolean = false,
) {
    val selectedDie: DieEntity? get() = dice.firstOrNull { it.id == selectedDieId }

    /** Face set of the selected die, driving the keypad; d6 when nothing is selected. */
    val dieType: DieType get() = selectedDie?.let { DieType.fromFaces(it.faces) } ?: DieType.DEFAULT

    val activeLine: RollLine get() = lines[activeLineIndex]

    /** True once any value has been entered, so there is something worth saving. */
    val canSave: Boolean get() = lines.any { it.results.isNotEmpty() }
}

/**
 * Drives the manual (photo-less) roll entry screen: the user picks a die from the carousel
 * and taps face values on the keypad; each tap drops a value into the active roll line with
 * no confirmation. "Next roll" starts a new line; tapping a line or a value re-opens it for
 * editing. Nothing is persisted until [save], which writes each non-empty line as a roll.
 */
@HiltViewModel
class ManualRollViewModel @Inject constructor(
    private val repository: DiceRepository,
) : ViewModel() {

    private var nextLocalId = 1L

    private val _uiState = MutableStateFlow(ManualRollUiState())
    val uiState: StateFlow<ManualRollUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.dice.collect { dice ->
                _uiState.update { state ->
                    // Default the selection to the most-recent die once dice exist.
                    val selected = state.selectedDieId?.takeIf { id -> dice.any { it.id == id } }
                        ?: dice.maxByOrNull { it.createdAt }?.id
                    state.copy(dice = dice, selectedDieId = selected)
                }
            }
        }
    }

    fun selectDie(dieId: Long) {
        _uiState.update { it.copy(selectedDieId = dieId, editingResultIndex = null) }
    }

    /** Registers a new die (or pool) from the carousel and selects it. */
    fun registerDie(name: String, faces: Int, count: Int) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val id = repository.registerDie(trimmed, faces, count)
            _uiState.update { it.copy(selectedDieId = id, editingResultIndex = null) }
        }
    }

    /** Handles a keypad face value: overwrites the selected chip, or adds to the active line. */
    fun tapValue(value: Int) {
        _uiState.update { state ->
            val die = state.selectedDie ?: return@update state
            val line = state.activeLine
            val editing = state.editingResultIndex
            val newResults = if (editing != null && editing in line.results.indices) {
                line.results.mapIndexed { i, r -> if (i == editing) r.copy(dieId = die.id, value = value) else r }
            } else {
                appendOrReplace(line.results, die, value)
            }
            state.copy(
                lines = state.lines.replaceAt(state.activeLineIndex, line.copy(results = newResults)),
                editingResultIndex = null,
            )
        }
    }

    /** Commits the active line and starts a fresh one (Enter / "Next roll"). */
    fun nextRoll() {
        _uiState.update { state ->
            if (state.activeLine.results.isEmpty()) return@update state
            // Reuse an existing trailing empty line if any, else append one.
            val trailingEmpty = state.lines.indexOfLast { it.results.isEmpty() }
                .takeIf { it > state.activeLineIndex }
            if (trailingEmpty != null) {
                state.copy(activeLineIndex = trailingEmpty, editingResultIndex = null)
            } else {
                state.copy(
                    lines = state.lines + RollLine(nextLocalId++),
                    activeLineIndex = state.lines.size,
                    editingResultIndex = null,
                )
            }
        }
    }

    /** Makes [index] the active line so further taps edit it. */
    fun selectLine(index: Int) {
        _uiState.update { state ->
            if (index !in state.lines.indices) return@update state
            state.copy(activeLineIndex = index, editingResultIndex = null)
        }
    }

    /** Selects a value in the active line to overwrite next, and matches the keypad to its die. */
    fun selectResult(index: Int) {
        _uiState.update { state ->
            val result = state.activeLine.results.getOrNull(index) ?: return@update state
            state.copy(editingResultIndex = index, selectedDieId = result.dieId)
        }
    }

    /** Removes a value from the active line. */
    fun deleteResult(index: Int) {
        _uiState.update { state ->
            val line = state.activeLine
            if (index !in line.results.indices) return@update state
            state.copy(
                lines = state.lines.replaceAt(
                    state.activeLineIndex,
                    line.copy(results = line.results.filterIndexed { i, _ -> i != index }),
                ),
                editingResultIndex = null,
            )
        }
    }

    /** Persists every non-empty line as its own roll, then signals the screen to leave. */
    fun save() {
        val state = _uiState.value
        if (!state.canSave || state.saved) return
        val toSave = state.lines.filter { it.results.isNotEmpty() }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            toSave.forEach { line ->
                repository.saveManualRoll(now, line.results.map { ManualDie(it.dieId, it.value) })
            }
            _uiState.update { it.copy(saved = true) }
        }
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, existing -> if (i == index) value else existing }
