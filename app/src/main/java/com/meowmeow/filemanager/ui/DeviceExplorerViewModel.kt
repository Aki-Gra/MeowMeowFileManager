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
import com.meowmeow.filemanager.data.CopyNode
import com.meowmeow.filemanager.data.CopyPlace
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.FileKind
import com.meowmeow.filemanager.model.SearchScope
import com.meowmeow.filemanager.model.searchStartFolder
import com.meowmeow.filemanager.model.FileDiscoveryScanner
import com.meowmeow.filemanager.model.SortMode
import com.meowmeow.filemanager.model.sortFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A separate direct-file backend. The original SAF browser remains available as a fallback. */
class DeviceExplorerViewModel(
    private val repository: LocalFileRepository,
    private val errorLog: ErrorLogStore,
    private val taskCenter: TaskCenterViewModel
) : ViewModel() {
    private val folders = mutableListOf<File>()
    private var scanJob: Job? = null
    private val _state = MutableStateFlow(ExplorerUiState(isLoading = false))
    val state: StateFlow<ExplorerUiState> = _state
    private val _search = MutableStateFlow(FileSearchState())
    val search: StateFlow<FileSearchState> = _search
    private var searchJob: Job? = null
    private val searchRoutes = mutableMapOf<String, List<File>>()

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

    fun createFolder(name: String) {
        val folder = folders.lastOrNull() ?: return
        mutate("Create folder", ErrorOperation.FS_CREATE, deviceTaskScope(folder)) {
            repository.createFolder(folder, name)
        }
    }

    fun rename(entry: FileEntry, newName: String) {
        val target = runCatching { repository.fromEntry(entry) }.getOrNull() ?: return
        // Both the old and new name are protected by locking their parent.
        val parent = target.parentFile ?: return
        mutate("Rename item", ErrorOperation.FS_RENAME, deviceTaskScope(parent)) {
            repository.rename(target, newName)
        }
    }

    fun delete(entry: FileEntry) {
        val target = runCatching { repository.fromEntry(entry) }.getOrNull() ?: return
        mutate("Delete item", ErrorOperation.FS_DELETE, deviceTaskScope(target)) {
            repository.delete(target)
        }
    }

    private fun mutate(action: String, operation: ErrorOperation,
        scope: com.meowmeow.filemanager.model.TaskScope, block: () -> Unit) {
        val locations = listOf(scope)
        if (!taskCenter.allowMutation(action, locations)) return
        var succeeded = false
        val mutation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = resultUnlessCancelled { withContext(Dispatchers.IO) { block() } }
            if (result.isSuccess) {
                succeeded = true
                loadFolder()
            } else {
                val failure = result.exceptionOrNull()!!
                errorLog.record(ErrorArea.DEVICE_FILE_OPERATION, failure, operation = operation)
                _state.value = _state.value.copy(errorMessage = failure.message ?: "Operation failed.")
            }
        }
        val token = taskCenter.begin(BackgroundTaskKind.FILE_MUTATION, scope,
            readOnly = false, cancel = { mutation.cancel() })
        mutation.invokeOnCompletion { cause ->
            // A provider may have committed the change before returning or failing.
            taskCenter.noteChanged(locations)
            taskCenter.complete(token, succeeded, cause is CancellationException || mutation.isCancelled)
        }
        mutation.start()
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

    /** Search current folder or the selected device-storage root. Read-only. */
    fun startSearch(query: String, kind: FileKind, scope: SearchScope) {
        cancelSearch()
        if (!repository.hasAccess()) {
            _search.value = FileSearchState(notice = "Grant file access before searching.")
            return
        }
        val startFolder = searchStartFolder(folders.firstOrNull(), folders.lastOrNull(), scope) ?: return
        if (!taskCenter.allowScan("Search device files", deviceTaskScope(startFolder))) return
        var taskToken = -1L
        val searchTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _search.value = FileSearchState(isRunning = true)
            val outcome = resultUnlessCancelled {
                withContext(Dispatchers.IO) {
                    FileDiscoveryScanner<File>(
                        identity = { it.path },
                        listChildren = { folder ->
                            repository.listChildren(folder).mapNotNull { entry ->
                                runCatching { entry to repository.fromEntry(entry) }.getOrNull()
                            }
                        }
                    ).scan(startFolder, scope.recursive, query, kind, onProgress = { visited, examined ->
                        withContext(Dispatchers.Main) {
                            _search.value = _search.value.copy(
                                foldersVisited = visited, entriesVisited = examined
                            )
                            taskCenter.progress(taskToken, visited, examined)
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
                errorLog.record(ErrorArea.DEVICE_BROWSING, it, operation = ErrorOperation.FS_SEARCH)
                _search.value = FileSearchState(
                    finished = true, notice = "Search could not be completed."
                )
            }
        }
        searchJob = searchTask
        val token = taskCenter.begin(
            BackgroundTaskKind.DEVICE_SEARCH, deviceTaskScope(startFolder), cancel = { searchTask.cancel() }
        )
        taskToken = token
        searchTask.invokeOnCompletion { cause ->
            if (cause is CancellationException && searchJob === searchTask) {
                _search.value = FileSearchState(notice = "Search stopped.")
            }
            taskCenter.complete(
                token,
                success = cause == null && _search.value.finished &&
                    _search.value.notice == null,
                wasCancelled = cause is CancellationException || searchTask.isCancelled
            )
        }
        searchTask.start()
    }

    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        searchRoutes.clear()
        _search.value = FileSearchState()
    }

    fun openSearchFolder(hit: FileSearchHit) {
        val route = searchRoutes[hit.entry.id]?.toList() ?: return
        if (!repository.hasAccess()) return
        val target = runCatching { repository.fromEntry(hit.entry) }.getOrNull()
        if (target == null || !target.isDirectory || route.lastOrNull() != target) {
            _state.value = _state.value.copy(errorMessage = "This folder is no longer accessible.")
            return
        }
        val fullRoute = when (route.firstOrNull()) {
            folders.firstOrNull() -> route
            folders.lastOrNull() -> folders.dropLast(1) + route
            else -> return
        }
        cancelSearch()
        folders.clear()
        folders.addAll(fullRoute)
        loadFolder()
    }

    /** Read-only scan starts from the explicit folder/search scope. */
    fun analysisFolder(scope: SearchScope): CopyPlace? {
        if (!repository.hasAccess()) return null
        val folder = searchStartFolder(folders.firstOrNull(), folders.lastOrNull(), scope)
            ?: return null
        return runCatching {
            CopyPlace(CopyNode.Device(folder), "device", folders.map { it.path })
        }.getOrNull()
    }

    fun copySelection(entry: FileEntry): CopyPlace? {
        if (_state.value.entries.none { it.id == entry.id }) return null
        return runCatching {
            CopyPlace(CopyNode.Device(repository.fromEntry(entry)),
                "device", folders.map { it.path })
        }.getOrNull()
    }

    fun copyDestination(): CopyPlace? {
        if (!repository.hasAccess() || !_state.value.canCreateFolder) return null
        return runCatching {
            CopyPlace(CopyNode.Device(current()), "device", folders.map { it.path })
        }.getOrNull()
    }

    fun clearError() { _state.value = _state.value.copy(errorMessage = null) }

    class Factory(context: Context, private val taskCenter: TaskCenterViewModel) : ViewModelProvider.Factory {
        private val errorLog = (context.applicationContext as MeowMeowApp).errorLog
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DeviceExplorerViewModel::class.java))
            return DeviceExplorerViewModel(LocalFileRepository(), errorLog, taskCenter) as T
        }
    }
}
