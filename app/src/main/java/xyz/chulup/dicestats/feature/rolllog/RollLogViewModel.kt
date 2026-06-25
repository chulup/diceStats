package xyz.chulup.dicestats.feature.rolllog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.db.RollWithResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * @param selectedIds ids of rolls selected for a bulk action; non-empty = selection mode.
 */
data class RollLogUiState(
    val rolls: List<RollWithResults> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
) {
    val inSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

@HiltViewModel
class RollLogViewModel @Inject constructor(
    private val repository: DiceRepository,
) : ViewModel() {

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<RollLogUiState> =
        combine(repository.rolls, selectedIds) { rolls, selected ->
            // Drop selection of rolls that no longer exist.
            val existing = rolls.mapTo(HashSet()) { it.roll.id }
            RollLogUiState(rolls = rolls, selectedIds = selected intersect existing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RollLogUiState())

    fun toggleSelection(id: Long) {
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        val ids = selectedIds.value.toList()
        if (ids.isEmpty()) return
        selectedIds.value = emptySet()
        viewModelScope.launch { repository.deleteRolls(ids) }
    }
}
