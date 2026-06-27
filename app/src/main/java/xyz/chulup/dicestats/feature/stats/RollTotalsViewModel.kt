package xyz.chulup.dicestats.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.DiceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class RollTotalsUiState(val groups: List<RollTotalGroup> = emptyList())

/** Distribution of roll totals, grouped by dice-count, across every recorded roll. */
@HiltViewModel
class RollTotalsViewModel @Inject constructor(
    repository: DiceRepository,
) : ViewModel() {

    val uiState: StateFlow<RollTotalsUiState> =
        repository.rolls
            .map { rolls -> RollTotalsUiState(RollTotalStatistics.from(rolls.map { it.results.map { r -> r.value } })) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RollTotalsUiState())
}
