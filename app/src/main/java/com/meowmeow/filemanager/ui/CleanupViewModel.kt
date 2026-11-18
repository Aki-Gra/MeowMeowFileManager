package com.meowmeow.filemanager.ui

import android.content.Context
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.data.LocalFileRepository
import com.meowmeow.filemanager.model.CleanupCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CleanupUiState(
    val isLoading: Boolean = false,
    val scanned: Boolean = false,
    val candidates: List<CleanupCandidate> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val truncated: Boolean = false,
    val message: String? = null
) {
    val selected: List<CleanupCandidate>
        get() = candidates.filter { it.id in selectedIds }
    val selectedBytes: Long get() = selected.sumOf { it.sizeBytes }
}

class CleanupViewModel(
    private val repository: LocalFileRepository,
    private val errorLog: ErrorLogStore
) : ViewModel() {
    private val _state = MutableStateFlow(CleanupUiState())
    val state: StateFlow<CleanupUiState> = _state
    private var task: Job? = null

    fun scan() {
        if (!repository.hasAccess()) {
            _state.value = CleanupUiState(message = "Grant All files access to scan Downloads.")
            return
        }
        task?.cancel()
        task = viewModelScope.launch {
            _state.value = CleanupUiState(isLoading = true)
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) { repository.scanLargeDownloads() }
            }
            result.onSuccess { (candidates, truncated) ->
                _state.value = CleanupUiState(
                    scanned = true,
                    candidates = candidates,
                    truncated = truncated
                )
            }.onFailure {
                errorLog.record(ErrorArea.CLEANUP_SCAN, it)
                _state.value = CleanupUiState(message = it.message ?: "Scan failed.")
            }
        }
    }

    fun toggleSelected(id: String) {
        val current = _state.value
        if (current.candidates.none { it.id == id }) return
        val selected = current.selectedIds.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        _state.value = current.copy(selectedIds = selected)
    }

    fun deleteSelected() {
        val items = _state.value.selected
        if (items.isEmpty() || _state.value.isLoading) return
        task?.cancel()
        task = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)
            val (successes, failed) = withContext(Dispatchers.IO) {
                val failureMessages = mutableListOf<String>()
                var deleted = 0
                items.forEach {
                    try {
                        repository.deleteDownload(it)
                        deleted++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorLog.record(ErrorArea.CLEANUP_DELETE, error)
                        failureMessages.add(error.message ?: "Could not delete file.")
                    }
                }
                deleted to failureMessages
            }
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) { repository.scanLargeDownloads() }
            }
            result.onSuccess { (remaining, truncated) ->
                val summary = if (failed.isEmpty()) {
                    "Deleted " + successes + " file(s)."
                } else {
                    "Deleted " + successes + "; " + failed.size + " failed. " + failed.first()
                }
                _state.value = CleanupUiState(
                    scanned = true,
                    candidates = remaining,
                    truncated = truncated,
                    message = summary
                )
            }.onFailure {
                errorLog.record(ErrorArea.CLEANUP_SCAN, it)
                _state.value = CleanupUiState(message = "Some files were processed, but refreshing failed.")
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val errorLog = (context.applicationContext as MeowMeowApp).errorLog
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CleanupViewModel::class.java))
            return CleanupViewModel(LocalFileRepository(), errorLog) as T
        }
    }
}
