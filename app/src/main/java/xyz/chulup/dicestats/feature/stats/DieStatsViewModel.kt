package xyz.chulup.dicestats.feature.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.DieType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DieStatsUiState(
    val dieName: String? = null,
    /** Physical dice behind this entry; > 1 means a pool and pooled statistics. */
    val dieCount: Int = 1,
    /** Distinct rolls the die appears in ([DieStatistics.total] counts throws). */
    val rollCount: Int = 0,
    val stats: DieStatistics = DieStatistics.from(emptyList()),
) {
    val isPool: Boolean get() = dieCount > 1
}

/** Per-die statistics screen: face distribution, mean, and chi-square fairness. */
@HiltViewModel
class DieStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: DiceRepository,
) : ViewModel() {

    private val dieId: Long =
        savedStateHandle.get<Long>(ARG_DIE_ID) ?: error("dieId argument required")

    val uiState: StateFlow<DieStatsUiState> =
        combine(
            repository.die(dieId),
            repository.valuesForDie(dieId),
            repository.rollCountForDie(dieId),
        ) { die, values, rollCount ->
            val dieType = die?.let { DieType.fromFaces(it.faces) } ?: DieType.DEFAULT
            DieStatsUiState(
                dieName = die?.name,
                dieCount = die?.count ?: 1,
                rollCount = rollCount,
                stats = DieStatistics.from(values, dieType),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DieStatsUiState())

    companion object {
        const val ARG_DIE_ID = "dieId"
    }
}
