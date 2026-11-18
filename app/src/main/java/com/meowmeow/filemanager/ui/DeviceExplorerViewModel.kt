package com.meowmeow.filemanager.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import com.meowmeow.filemanager.data.LocalFileRepository
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SortMode
import com.meowmeow.filemanager.model.sortFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A separate direct-file backend. The original SAF browser remains available as a fallback. */
class DeviceExplorerViewModel(
    private val repository: LocalFileRepository,
    private val errorLog: ErrorLogStore
) : ViewModel() {
    private val folders = mutableListOf<File>()
    private var scanJob: Job? = null
    private val _state = MutableStateFlow(ExplorerUiState(isLoading = false))
    val state: StateFlow<ExplorerUiState> = _state

    fun openRoot() {
        if (!repository.hasAccess()) {
            _state.value = ExplorerUiState(isLoading = false)
            return
        }
        folders.clear()
        folders.add(repository.root())
        loadFolder()
    }

    fun openDownloads() {
        if (!repository.hasAccess()) return
        folders.clear()
        folders.add(repository.root())
        folders.add(repository.downloads())
        loadFolder()
    }

    fun openDirectory(entry: FileEntry) {
        if (!entry.isDirectory) return
        val target = runCatching { repository.fromEntry(entry) }
            .onFailure { errorLog.record(ErrorArea.DEVICE_BROWSING, it, operation = ErrorOperation.FS_OPEN_FOLDER) }
            .getOrNull() ?: return
        if (target.parentFile != folders.lastOrNull() || !target.isDirectory) return
        folders.add(target)
        loadFolder()
    }

    fun navigateUp() {
        if (folders.size < 2) return
        folders.removeAt(folders.lastIndex)
        loadFolder()
    }

    fun refresh() {
        if (folders.isEmpty()) openRoot() else loadFolder()
    }

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(
            sortMode = mode,
            entries = sortFiles(_state.value.entries, mode)
        )
    }

    fun createFolder(name: String) = mutate(ErrorOperation.FS_CREATE) {
        repository.createFolder(current(), name)
    }
    fun rename(entry: FileEntry, newName: String) = mutate(ErrorOperation.FS_RENAME) {
        repository.rename(repository.fromEntry(entry), newName)
    }
    fun delete(entry: FileEntry) = mutate(ErrorOperation.FS_DELETE) {
        repository.delete(repository.fromEntry(entry))
    }

    private fun mutate(operation: ErrorOperation, block: () -> Unit) {
        viewModelScope.launch {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { block() } }
            if (result.isSuccess) loadFolder()
            else {
                val failure = result.exceptionOrNull()!!
                errorLog.record(ErrorArea.DEVICE_FILE_OPERATION, failure, operation = operation)
                _state.value = _state.value.copy(
                    errorMessage = failure.message ?: "Operation failed."
                )
            }
        }
    }

    private fun current(): File = folders.lastOrNull() ?: error("No folder is open.")

    private fun loadFolder() {
        if (!repository.hasAccess()) {
            _state.value = ExplorerUiState(isLoading = false)
            return
        }
        val folder = current()
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                hasRoot = true,
                path = folders.map { if (it == folders.first()) "Device storage" else it.name },
                entries = emptyList(),
                canCreateFolder = false,
                isLoading = true,
                errorMessage = null
            )
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) {
                    repository.listChildren(folder) to repository.canCreateFolder(folder)
                }
            }
            result.onSuccess { (files, writable) ->
                _state.value = _state.value.copy(
                    entries = sortFiles(files, _state.value.sortMode),
                    canCreateFolder = writable,
                    isLoading = false
                )
            }.onFailure {
                errorLog.record(ErrorArea.DEVICE_BROWSING, it, operation = ErrorOperation.FS_LIST)
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = it.message ?: "Could not open this folder."
                )
            }
        }
    }

    fun clearError() { _state.value = _state.value.copy(errorMessage = null) }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val errorLog = (context.applicationContext as MeowMeowApp).errorLog
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DeviceExplorerViewModel::class.java))
            return DeviceExplorerViewModel(LocalFileRepository(), errorLog) as T
        }
    }
}
