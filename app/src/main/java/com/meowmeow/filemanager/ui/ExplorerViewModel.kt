package com.meowmeow.filemanager.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.data.DocumentRepository
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SortMode
import com.meowmeow.filemanager.model.sortFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExplorerUiState(
    val hasRoot: Boolean = false,
    val path: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val sortMode: SortMode = SortMode.NAME,
    val canCreateFolder: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class ExplorerViewModel(private val repository: DocumentRepository) : ViewModel() {
    private data class FolderLevel(val name: String, val file: DocumentFile)

    private val folders = mutableListOf<FolderLevel>()
    private var children: Map<String, DocumentFile> = emptyMap()
    private var scanJob: Job? = null
    private var selectionJob: Job? = null

    private val _state = MutableStateFlow(ExplorerUiState())
    val state: StateFlow<ExplorerUiState> = _state

    init {
        selectionJob = viewModelScope.launch {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { repository.restoreRoot() } }
            val root = result.getOrNull()
            if (root == null) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = result.exceptionOrNull()?.let { friendlyError(it) }
                )
            } else {
                enterRoot(root)
            }
        }
    }

    fun chooseTree(uri: Uri) {
        selectionJob?.cancel()
        scanJob?.cancel()
        selectionJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) { repository.chooseRoot(uri) }
            }
            result.onSuccess(::enterRoot)
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(it))
                }
        }
    }

    private fun enterRoot(root: DocumentFile) {
        folders.clear()
        folders += FolderLevel(root.name ?: "Selected folder", root)
        children = emptyMap()
        loadFolder()
    }

    fun openDirectory(entry: FileEntry) {
        val file = children[entry.id] ?: return
        if (!file.isDirectory) return
        folders += FolderLevel(entry.name, file)
        loadFolder()
    }

    fun navigateUp() {
        if (folders.size <= 1) return
        folders.removeAt(folders.lastIndex)
        loadFolder()
    }

    fun refresh() = loadFolder()

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(
            sortMode = mode,
            entries = sortFiles(_state.value.entries, mode)
        )
    }

    fun createFolder(name: String) = mutate {
        repository.createFolder(currentFolder() ?: error("No folder selected"), name)
    }

    fun rename(entry: FileEntry, newName: String) = mutate {
        repository.rename(children[entry.id] ?: error("File is no longer available"), newName)
    }

    fun delete(entry: FileEntry) = mutate {
        repository.delete(children[entry.id] ?: error("File is no longer available"))
    }

    private fun mutate(block: () -> Unit) {
        viewModelScope.launch {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { block() } }
            if (result.isSuccess) loadFolder()
            else _state.value = _state.value.copy(errorMessage = friendlyError(result.exceptionOrNull()!!))
        }
    }

    private fun currentFolder(): DocumentFile? = folders.lastOrNull()?.file

    private fun loadFolder() {
        val folder = currentFolder() ?: return
        scanJob?.cancel()
        // Never perform provider I/O on the main thread.
        scanJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                hasRoot = true,
                path = folders.map { it.name },
                entries = emptyList(),
                canCreateFolder = false,
                isLoading = true,
                errorMessage = null
            )
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) {
                    val files = repository.listChildren(folder)
                    files to folder.canWrite()
                }
            }
            result.onSuccess { (files, canWrite) ->
                children = files.associate { it.first.id to it.second }
                _state.value = _state.value.copy(
                    entries = sortFiles(files.map { it.first }, _state.value.sortMode),
                    canCreateFolder = canWrite,
                    isLoading = false
                )
            }.onFailure {
                children = emptyMap()
                _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(it))
            }
        }
    }

    fun clearError() { _state.value = _state.value.copy(errorMessage = null) }

    private fun friendlyError(t: Throwable): String = when (t) {
        is SecurityException -> "Folder access was denied or revoked. Choose a folder again."
        else -> t.message ?: "The file operation failed. Try again."
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = DocumentRepository(context.applicationContext)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExplorerViewModel::class.java))
            return ExplorerViewModel(repository) as T
        }
    }
}
