package xyz.chulup.dicestats.feature.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.chulup.dicestats.data.DiceRepository
import javax.inject.Inject

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val repository: DiceRepository,
) : ViewModel() {

    val uiState: StateFlow<GamesUiState> =
        combine(repository.games, repository.rollCountsByGame) { games, counts ->
            buildGamesUiState(games, counts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamesUiState())

    fun startGame(name: String, usesDicePools: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.startGame(trimmed, usesDicePools) }
    }

    fun finishGame(id: Long) {
        viewModelScope.launch { repository.finishGame(id) }
    }
}
