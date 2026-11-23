package com.meowmeow.filemanager.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.data.AndroidCopyOperations
import com.meowmeow.filemanager.data.CopyNode
import com.meowmeow.filemanager.data.CopyPlace
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.model.AnalysisKind
import com.meowmeow.filemanager.model.AnalysisItem
import com.meowmeow.filemanager.model.FileAnalysisScanner
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SearchScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileAnalysisHit(
    val entry: FileEntry,
    val location: String
)
data class DuplicateGroupResult(
    val members: List<FileAnalysisHit>,
    val sizeBytes: Long,
    val reclaimableBytes: Long
)
data class FileAnalysisUiState(
    val isRunning: Boolean = false,
    val complete: Boolean = false,
    val kind: AnalysisKind = AnalysisKind.LARGE,
    val thresholdMiB: Int = 50,
    val scope: SearchScope = SearchScope.CURRENT_FOLDER,
    val foldersVisited: Int = 0,
    val itemsVisited: Int = 0,
    val hashedFiles: Int = 0,
    val unreadable: Int = 0,
    val truncated: Boolean = false,
    val large: List<FileAnalysisHit> = emptyList(),
    val duplicateGroups: List<DuplicateGroupResult> = emptyList(),
    val reclaimableBytes: Long = 0L,
    val notice: String? = null
)

class FileAnalysisViewModel(
    private val ops: AndroidCopyOperations,
    private val errors: ErrorLogStore
) : ViewModel() {
    private val _state = MutableStateFlow(FileAnalysisUiState())
    val state: StateFlow<FileAnalysisUiState> = _state
    private var job: Job? = null
    private var scanGeneration = 0L

    fun reset() {
        scanGeneration++
        job?.cancel()
        job = null
        _state.value = FileAnalysisUiState()
    }

    fun cancel() {
        if (!_state.value.isRunning) return
        scanGeneration++
        job?.cancel()
        job = null
        _state.value = FileAnalysisUiState(notice = "Scan cancelled. No changes were made.")
    }

    fun scan(root: CopyPlace?, kind: AnalysisKind, scope: SearchScope, minimumMiB: Int) {
        scanGeneration++
        val generation = scanGeneration
        job?.cancel()
        if (root == null) {
            _state.value = FileAnalysisUiState(
                kind = kind, scope = scope, thresholdMiB = minimumMiB,
                notice = "Open an accessible folder in Files or grant storage access first."
            )
            return
        }
        job = viewModelScope.launch {
            _state.value = FileAnalysisUiState(
                isRunning = true, kind = kind, scope = scope, thresholdMiB = minimumMiB
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    FileAnalysisScanner(ops).scan(
                        root = root.node,
                        kind = kind,
                        recursive = scope.recursive,
                        minimumBytes = minimumMiB.toLong() * 1024L * 1024L,
                        onProgress = { folders, entries, hashed ->
                            withContext(Dispatchers.Main) {
                                _state.value = _state.value.copy(
                                    foldersVisited = folders,
                                    itemsVisited = entries,
                                    hashedFiles = hashed
                                )
                            }
                        }
                    )
                }
                fun hit(item: AnalysisItem<CopyNode>): FileAnalysisHit {
                    val id = when (val node = item.node) {
                        is CopyNode.Device -> Uri.fromFile(node.file).toString()
                        is CopyNode.Scoped -> node.file.uri.toString()
                    }
                    val type = runCatching { ops.mime(item.node) }.getOrNull()
                    return FileAnalysisHit(
                        FileEntry(
                            id = id, name = item.name, isDirectory = false,
                            sizeBytes = item.size, modifiedAtMillis = 0L,
                            mimeType = type, canWrite = false
                        ), item.relativeFolder
                    )
                }
                _state.value = FileAnalysisUiState(
                    complete = true,
                    kind = kind,
                    thresholdMiB = minimumMiB,
                    scope = scope,
                    foldersVisited = result.scannedFolders,
                    itemsVisited = result.scannedFiles,
                    hashedFiles = result.hashedFiles,
                    unreadable = result.unreadable,
                    truncated = result.truncated,
                    large = result.largeFiles.map(::hit),
                    duplicateGroups = result.duplicateGroups.map { group ->
                        DuplicateGroupResult(
                            members = group.members.map(::hit),
                            sizeBytes = group.fileSize,
                            reclaimableBytes = group.estimatedReclaimableBytes
                        )
                    },
                    reclaimableBytes = result.reclaimableBytes
                )
            } catch (_: CancellationException) {
                // Expected user navigation/cancellation: never log.
            } catch (failure: Exception) {
                errors.record(
                    ErrorArea.FILE_ANALYSIS, failure,
                    operation = if (kind == AnalysisKind.DUPLICATES)
                        ErrorOperation.DUPLICATE_SCAN else ErrorOperation.LARGE_FILE_SCAN
                )
                _state.value = _state.value.copy(
                    isRunning = false,
                    notice = "Scan failed. Confirm that the selected folder is still accessible."
                )
            } finally {
                if (generation == scanGeneration) job = null
            }
        }
    }

    class Factory(context: Context): ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileAnalysisViewModel::class.java))
            return FileAnalysisViewModel(
                AndroidCopyOperations(app), (app as MeowMeowApp).errorLog
            ) as T
        }
    }
}
