package xyz.chulup.dicestats.feature.games

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import xyz.chulup.dicestats.data.DiceRepository
import javax.inject.Inject

/** Stats for all dice in a single game: aggregate roll totals + per-die fairness. */
@HiltViewModel
class GameStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: DiceRepository,
) : ViewModel() {

    private val gameId: Long =
        savedStateHandle.get<Long>(ARG_GAME_ID) ?: error("gameId argument required")

    val uiState: StateFlow<GameStatsUiState> =
        combine(
            repository.game(gameId),
            repository.rollsForGame(gameId),
            repository.dice,
        ) { game, rolls, dice ->
            buildGameStatsUiState(game?.name, rolls, dice)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameStatsUiState())

    companion object {
        const val ARG_GAME_ID = "gameId"
    }
}
