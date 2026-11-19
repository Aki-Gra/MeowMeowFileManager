package com.meowmeow.filemanager.ui

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import com.meowmeow.filemanager.data.DocumentRepository
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.FileKind
import com.meowmeow.filemanager.model.FileDiscoveryScanner
import com.meowmeow.filemanager.model.SortMode
import com.meowmeow.filemanager.model.sortFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ExplorerUiState(
    val hasRoot: Boolean = false,
    val path: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val sortMode: SortMode = SortMode.NAME,
    val canCreateFolder: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class ExplorerViewModel(
    private val repository: DocumentRepository,
    private val errorLog: ErrorLogStore
) : ViewModel() {
    private data class FolderLevel(val name: String, val file: DocumentFile)

    private val folders = mutableListOf<FolderLevel>()
    private var children: Map<String, DocumentFile> = emptyMap()
    private var scanJob: Job? = null
    private var selectionJob: Job? = null

    private val _state = MutableStateFlow(ExplorerUiState())
    val state: StateFlow<ExplorerUiState> = _state
    private val _search = MutableStateFlow(FileSearchState())
    val search: StateFlow<FileSearchState> = _search
    private var searchJob: Job? = null
    private val searchRoutes = mutableMapOf<String, List<DocumentFile>>()

    init {
        selectionJob = viewModelScope.launch {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { repository.restoreRoot() } }
            val root = result.getOrNull()
            if (root == null) {
                result.exceptionOrNull()?.let { errorLog.record(ErrorArea.SCOPED_BROWSING, it, operation = ErrorOperation.SAF_RESTORE) }
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
        cancelSearch()
        selectionJob?.cancel()
        scanJob?.cancel()
        selectionJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = resultUnlessCancelled {
                withContext(Dispatchers.IO) { repository.chooseRoot(uri) }
            }
            result.onSuccess(::enterRoot)
                .onFailure {
                    errorLog.record(ErrorArea.SCOPED_FOLDER_PICKER, it, operation = ErrorOperation.SAF_PICK)
                    _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(it))
                }
        }
    }

    private fun enterRoot(root: DocumentFile) {
        cancelSearch()
        folders.clear()
        folders += FolderLevel(root.name ?: "Selected folder", root)
        children = emptyMap()
        loadFolder()
    }

    fun openDirectory(entry: FileEntry) {
        val file = children[entry.id] ?: return
        cancelSearch()
        if (!file.isDirectory) return
        folders += FolderLevel(entry.name, file)
        loadFolder()
    }

    fun navigateUp() {
        if (folders.size <= 1) return
        cancelSearch()
        folders.removeAt(folders.lastIndex)
        loadFolder()
    }

    fun refresh() {
        cancelSearch()
        loadFolder()
    }

    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(
            sortMode = mode,
            entries = sortFiles(_state.value.entries, mode)
        )
    }

    fun createFolder(name: String) = mutate(ErrorOperation.SAF_CREATE) {
        repository.createFolder(currentFolder() ?: error("No folder selected"), name)
    }

    fun rename(entry: FileEntry, newName: String) = mutate(ErrorOperation.SAF_RENAME) {
        repository.rename(children[entry.id] ?: error("File is no longer available"), newName)
    }

    fun delete(entry: FileEntry) = mutate(ErrorOperation.SAF_DELETE) {
        repository.delete(children[entry.id] ?: error("File is no longer available"))
    }

    private fun mutate(operation: ErrorOperation, block: () -> Unit) {
        viewModelScope.launch {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { block() } }
            if (result.isSuccess) loadFolder()
            else {
                val failure = result.exceptionOrNull()!!
                errorLog.record(ErrorArea.SCOPED_FILE_OPERATION, failure, operation = operation)
                _state.value = _state.value.copy(errorMessage = friendlyError(failure))
            }
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
                errorLog.record(ErrorArea.SCOPED_BROWSING, it, operation = ErrorOperation.SAF_LIST)
                children = emptyMap()
                _state.value = _state.value.copy(isLoading = false, errorMessage = friendlyError(it))
            }
        }
    }

    /** Only traverses the user's explicitly selected and persisted SAF tree. */
    fun startSearch(query: String, kind: FileKind, recursive: Boolean) {
        cancelSearch()
        val scope = (if (recursive) folders.firstOrNull() else folders.lastOrNull())?.file ?: return
        searchJob = viewModelScope.launch {
            _search.value = FileSearchState(isRunning = true)
            val outcome = resultUnlessCancelled {
                withContext(Dispatchers.IO) {
                    FileDiscoveryScanner<DocumentFile>(
                        identity = { it.uri.toString() },
                        listChildren = { folder -> repository.listChildren(folder) }
                    ).scan(scope, recursive, query, kind, onProgress = { visited, examined ->
                        withContext(Dispatchers.Main) {
                            _search.value = _search.value.copy(
                                foldersVisited = visited, entriesVisited = examined
                            )
                        }
                    })
                }
            }
            outcome.onSuccess { result ->
                searchRoutes.clear()
                result.matches.filter { it.entry.isDirectory }.forEach {
                    searchRoutes[it.entry.id] = it.folderTrail
                }
                _search.value = FileSearchState(
                    finished = true,
                    hits = result.matches.map { FileSearchHit(it.entry, it.location) },
                    foldersVisited = result.foldersVisited,
                    entriesVisited = result.entriesVisited,
                    unreadableFolders = result.unreadableFolders,
                    truncated = result.truncated
                )
            }.onFailure {
                errorLog.record(ErrorArea.SCOPED_BROWSING, it, operation = ErrorOperation.SAF_SEARCH)
                _search.value = FileSearchState(
                    finished = true, notice = "Search could not be completed."
                )
            }
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        searchRoutes.clear()
        _search.value = FileSearchState()
    }

    fun openSearchFolder(hit: FileSearchHit) {
        val route = searchRoutes[hit.entry.id]?.toList() ?: return
        if (route.firstOrNull()?.uri != folders.firstOrNull()?.file?.uri ||
            route.lastOrNull()?.uri.toString() != hit.entry.id) {
            _state.value = _state.value.copy(errorMessage = "This folder is no longer accessible.")
            return
        }
        cancelSearch()
        folders.clear()
        folders.addAll(route.mapIndexed { index, folder ->
            FolderLevel(if (index == 0) folder.name ?: "Selected folder"
                else folder.name ?: "Folder", folder)
        })
        children = emptyMap()
        loadFolder()
    }

    fun clearError() { _state.value = _state.value.copy(errorMessage = null) }

    private fun friendlyError(t: Throwable): String = when (t) {
        is SecurityException -> "Folder access was denied or revoked. Choose a folder again."
        else -> t.message ?: "The file operation failed. Try again."
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = DocumentRepository(context.applicationContext)
        private val errorLog = (context.applicationContext as MeowMeowApp).errorLog

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExplorerViewModel::class.java))
            return ExplorerViewModel(repository, errorLog) as T
        }
    }
}
