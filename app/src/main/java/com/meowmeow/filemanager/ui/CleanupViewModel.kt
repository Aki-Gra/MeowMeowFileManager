package com.meowmeow.filemanager.ui

import android.content.Context
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.data.LocalFileRepository
import com.meowmeow.filemanager.model.TaskScope
import android.os.Environment
import com.meowmeow.filemanager.model.CleanupCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val errorLog: ErrorLogStore,
    private val taskCenter: TaskCenterViewModel
) : ViewModel() {
    private val _state = MutableStateFlow(CleanupUiState())
    val state: StateFlow<CleanupUiState> = _state
    private var task: Job? = null
    private val downloadsScope: TaskScope
        get() = deviceTaskScope(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )

    fun scan() {
        if (_state.value.isLoading) return
        if (!taskCenter.allowScan("Scan Downloads", downloadsScope)) return
        if (!repository.hasAccess()) {
            _state.value = CleanupUiState(message = "Grant All files access to scan Downloads.")
            return
        }
        task?.cancel()
        _state.value = CleanupUiState(isLoading = true)
        val scanTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
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
                errorLog.record(ErrorArea.CLEANUP_SCAN, it, operation = ErrorOperation.CLEANUP_SCAN)
                _state.value = CleanupUiState(message = it.message ?: "Scan failed.")
            }
        }
        task = scanTask
        val token = taskCenter.begin(
            BackgroundTaskKind.DOWNLOADS_SCAN, downloadsScope,
            cancel = { scanTask.cancel() }
        )
        scanTask.invokeOnCompletion { cause ->
            if (cause is CancellationException && task === scanTask) {
                _state.value = CleanupUiState(message = "Downloads scan stopped.")
            }
            taskCenter.complete(
                token, success = cause == null && _state.value.scanned,
                wasCancelled = cause is CancellationException || scanTask.isCancelled
            )
        }
        scanTask.start()
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
        if (!taskCenter.allowMutation("Delete Downloads files", listOf(downloadsScope))) return
        task?.cancel()
        _state.value = _state.value.copy(isLoading = true, message = null)
        var deletedCount = 0
        var allSucceeded = false
        val deleteTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
            val (successes, failed) = withContext(Dispatchers.IO) {
                val failureMessages = mutableListOf<String>()
                items.forEach {
                    currentCoroutineContext().ensureActive()
                    try {
                        repository.deleteDownload(it)
                        deletedCount++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorLog.record(ErrorArea.CLEANUP_DELETE, error, operation = ErrorOperation.CLEANUP_DELETE)
                        failureMessages.add(error.message ?: "Could not delete file.")
                    }
                }
                deletedCount to failureMessages
            }
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) { repository.scanLargeDownloads() }
            }
            result.onSuccess { (remaining, truncated) ->
                allSucceeded = failed.isEmpty()
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
                errorLog.record(ErrorArea.CLEANUP_SCAN, it, operation = ErrorOperation.CLEANUP_SCAN)
                _state.value = CleanupUiState(message = "Some files were processed, but refreshing failed.")
            }
            } catch (cancelled: CancellationException) {
                _state.value = CleanupUiState(
                    message = "Cleanup stopped. $deletedCount file(s) deleted. Scan again to refresh."
                )
                throw cancelled
            }
        }
        task = deleteTask
        val token = taskCenter.begin(
            BackgroundTaskKind.DOWNLOADS_DELETE, downloadsScope,
            readOnly = false, cancel = { deleteTask.cancel() }
        )
        deleteTask.invokeOnCompletion { cause ->
            taskCenter.complete(
                token, success = cause == null && allSucceeded,
                wasCancelled = cause is CancellationException || deleteTask.isCancelled
            )
        }
        deleteTask.start()
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    class Factory(context: Context, private val taskCenter: TaskCenterViewModel) : ViewModelProvider.Factory {
        private val errorLog = (context.applicationContext as MeowMeowApp).errorLog
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CleanupViewModel::class.java))
            return CleanupViewModel(LocalFileRepository(), errorLog, taskCenter) as T
        }
    }
}
