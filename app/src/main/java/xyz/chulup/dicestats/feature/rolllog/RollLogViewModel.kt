package xyz.chulup.dicestats.feature.rolllog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import xyz.chulup.dicestats.data.photo.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @param selectedPaths absolute paths of photos currently selected for a bulk action.
 *   A non-empty set puts the screen in selection mode.
 */
data class RollLogUiState(
    val photos: List<File> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
) {
    val inSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
}

class RollLogViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = PhotoStorage(application)

    private val _uiState = MutableStateFlow(RollLogUiState())
    val uiState: StateFlow<RollLogUiState> = _uiState.asStateFlow()

    /** Reloads the photo list, dropping any selection of files that no longer exist. */
    fun refresh() {
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) { storage.listPhotos() }
            val existing = photos.mapTo(HashSet()) { it.absolutePath }
            _uiState.update { it.copy(photos = photos, selectedPaths = it.selectedPaths intersect existing) }
        }
    }

    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val selected = state.selectedPaths.toMutableSet()
            if (!selected.add(path)) selected.remove(path)
            state.copy(selectedPaths = selected)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet()) }
    }

    fun deleteSelected() {
        val toDelete = _uiState.value.selectedPaths
        if (toDelete.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { storage.deletePhotos(toDelete) }
            val photos = withContext(Dispatchers.IO) { storage.listPhotos() }
            _uiState.update { it.copy(photos = photos, selectedPaths = emptySet()) }
        }
    }
}
