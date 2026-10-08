package xyz.chulup.dicestats.feature.dicemanage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.chulup.dicestats.data.DiceRepository
import xyz.chulup.dicestats.data.DieType
import xyz.chulup.dicestats.feature.stats.DieStatistics
import xyz.chulup.dicestats.feature.stats.FairnessVerdict
import javax.inject.Inject

data class ManagedDie(
    val id: Long,
    val name: String,
    /** Physical dice behind the entry; > 1 is a group (pool). */
    val dieCount: Int,
    val type: DieType,
    val rollCount: Int,
    val verdict: FairnessVerdict,
    val picture: DiePicture?,
)

data class DiceManageUiState(val dice: List<ManagedDie> = emptyList())

@HiltViewModel
class DiceManageViewModel @Inject constructor(
    private val repository: DiceRepository,
) : ViewModel() {

    val uiState: StateFlow<DiceManageUiState> =
        combine(repository.dice, repository.rolls) { dice, rolls ->
            val valuesByDie = rolls.flatMap { it.results }.filter { it.dieId != null }
                .groupBy({ it.dieId!! }, { it.value })
            val rollsByDie = rolls.flatMap { rwr -> rwr.results.mapNotNull { it.dieId }.distinct() }
                .groupingBy { it }.eachCount()
            DiceManageUiState(
                dice.map { die ->
                    val type = DieType.fromFaces(die.faces)
                    ManagedDie(
                        id = die.id,
                        name = die.name,
                        dieCount = die.count,
                        type = type,
                        rollCount = rollsByDie[die.id] ?: 0,
                        verdict = DieStatistics.from(valuesByDie[die.id].orEmpty(), type).verdict,
                        picture = pickBestPicture(die.id, die.count, rolls),
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiceManageUiState())

    fun deleteDie(dieId: Long) {
        viewModelScope.launch { repository.deleteDie(dieId) }
    }
}
