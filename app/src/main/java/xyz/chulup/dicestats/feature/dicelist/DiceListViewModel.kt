package xyz.chulup.dicestats.feature.dicelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.DiceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A registered die plus its recorded-roll count, for the dice list. */
data class DieListItem(val id: Long, val name: String, val dieCount: Int, val rollCount: Int)

data class DiceListUiState(val dice: List<DieListItem> = emptyList())

@HiltViewModel
class DiceListViewModel @Inject constructor(
    repository: DiceRepository,
) : ViewModel() {

    val uiState: StateFlow<DiceListUiState> =
        combine(repository.dice, repository.rollCountsByDie) { dice, counts ->
            val countById = counts.associate { it.dieId to it.count }
            DiceListUiState(
                dice = dice.map { DieListItem(it.id, it.name, it.count, countById[it.id] ?: 0) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiceListUiState())
}
