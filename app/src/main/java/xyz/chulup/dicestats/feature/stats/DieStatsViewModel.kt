package xyz.chulup.dicestats.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.DiceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DieStatsUiState(
    val dieName: String? = null,
    val stats: DieStatistics = DieStatistics.from(emptyList()),
)

/** Per-die statistics screen: face distribution, mean, and chi-square fairness. */
@HiltViewModel
class DieStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: DiceRepository,
) : ViewModel() {

    private val dieId: Long =
        savedStateHandle.get<Long>(ARG_DIE_ID) ?: error("dieId argument required")

    val uiState: StateFlow<DieStatsUiState> =
        combine(repository.die(dieId), repository.valuesForDie(dieId)) { die, values ->
            DieStatsUiState(dieName = die?.name, stats = DieStatistics.from(values))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DieStatsUiState())

    companion object {
        const val ARG_DIE_ID = "dieId"
    }
}
