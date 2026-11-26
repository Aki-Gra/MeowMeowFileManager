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
import com.meowmeow.filemanager.model.AnalysisOutcome
import com.meowmeow.filemanager.model.FileAnalysisScanner
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SearchScope
import com.meowmeow.filemanager.model.DuplicateDeleteTarget
import com.meowmeow.filemanager.model.DuplicateDeleteSummary
import com.meowmeow.filemanager.model.DuplicateDeleteStatus
import com.meowmeow.filemanager.model.VerifiedDuplicateDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
    val selectedDuplicateIds: Set<String> = emptySet(),
    val pendingDeleteIds: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val deleteSummary: DuplicateDeleteSummary? = null,
    val deleteNotice: String? = null,
    val notice: String? = null
)

/** Prepared entirely on IO so DocumentsProvider lookups never block Main. */
private data class PreparedAnalysis(
    val outcome: AnalysisOutcome<CopyNode>,
    val large: List<FileAnalysisHit>,
    val groups: List<DuplicateGroupResult>,
    val targets: Map<String, DuplicateDeleteTarget<CopyNode>>,
    val additionalUnreadable: Int
)

class FileAnalysisViewModel(
    private val ops: AndroidCopyOperations,
    private val errors: ErrorLogStore,
    private val taskCenter: TaskCenterViewModel
) : ViewModel() {
    private val _state = MutableStateFlow(FileAnalysisUiState())
    val state: StateFlow<FileAnalysisUiState> = _state
    private var job: Job? = null
    private var deleteJob: Job? = null
    private val verifiedTargets = linkedMapOf<String, DuplicateDeleteTarget<CopyNode>>()
    private var scanGeneration = 0L

    fun reset() {
        if (_state.value.isDeleting) return
        verifiedTargets.clear()
        scanGeneration++
        job?.cancel()
        job = null
        _state.value = FileAnalysisUiState()
    }

    fun cancel() {
        if (!_state.value.isRunning) return
        verifiedTargets.clear()
        scanGeneration++
        job?.cancel()
        job = null
        _state.value = FileAnalysisUiState(notice = "Scan cancelled. No changes were made.")
    }

    fun scan(root: CopyPlace?, kind: AnalysisKind, scope: SearchScope, minimumMiB: Int) {
        if (_state.value.isDeleting) return
        if (root != null && !taskCenter.allowScan("Start file analysis", copyTaskScope(root))) return
        verifiedTargets.clear()
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
        var taskToken = -1L
        val scanTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _state.value = FileAnalysisUiState(
                isRunning = true, kind = kind, scope = scope, thresholdMiB = minimumMiB
            )
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val result = FileAnalysisScanner(ops).scan(
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
                                taskCenter.progress(taskToken, folders, entries, hashed)
                            }
                        }
                    )
                    fun hit(item: AnalysisItem<CopyNode>): FileAnalysisHit {
                        val id = when (val node = item.node) {
                            is CopyNode.Device -> Uri.fromFile(node.file).toString()
                            is CopyNode.Scoped -> node.file.uri.toString()
                        }
                        val mime = runCatching { ops.mime(item.node) }.getOrNull()
                        return FileAnalysisHit(
                            FileEntry(
                                id = id, name = item.name, isDirectory = false,
                                sizeBytes = item.size, modifiedAtMillis = item.modifiedAtMillis,
                                mimeType = mime, canWrite = false
                            ), item.relativeFolder
                        )
                    }

                    val targets = linkedMapOf<String, DuplicateDeleteTarget<CopyNode>>()
                    var unavailable = 0
                    val groups = result.duplicateGroups.mapNotNull { group ->
                        val available = group.members.mapNotNull { item ->
                            try {
                                val entry = hit(item)
                                targets[entry.entry.id] = DuplicateDeleteTarget(
                                    node = item.node,
                                    identity = ops.identity(item.node),
                                    name = item.name,
                                    size = item.size,
                                    modifiedAtMillis = item.modifiedAtMillis,
                                    sha256 = group.sha256
                                )
                                entry
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                unavailable++
                                null
                            }
                        }
                        if (available.size < 2) {
                            available.forEach { targets.remove(it.entry.id) }
                            null
                        } else DuplicateGroupResult(
                            members = available,
                            sizeBytes = group.fileSize,
                            reclaimableBytes = group.fileSize.coerceAtLeast(0) *
                                (available.size - 1).toLong()
                        )
                    }
                    PreparedAnalysis(
                        outcome = result,
                        large = result.largeFiles.map(::hit),
                        groups = groups,
                        targets = targets.toMap(),
                        additionalUnreadable = unavailable
                    )
                }
                // Only publish immutable snapshots after all provider I/O completes.
                verifiedTargets.clear()
                verifiedTargets.putAll(prepared.targets)
                val result = prepared.outcome
                _state.value = FileAnalysisUiState(
                    complete = true,
                    kind = kind,
                    thresholdMiB = minimumMiB,
                    scope = scope,
                    foldersVisited = result.scannedFolders,
                    itemsVisited = result.scannedFiles,
                    hashedFiles = result.hashedFiles,
                    unreadable = result.unreadable + prepared.additionalUnreadable,
                    truncated = result.truncated,
                    large = prepared.large,
                    duplicateGroups = prepared.groups,
                    reclaimableBytes = prepared.groups.sumOf { it.reclaimableBytes }
                )
            } catch (_: CancellationException) {
                // Explicit cancellation or permission loss; no error-log entry.
                if (generation == scanGeneration)
                    _state.value = FileAnalysisUiState(
                        kind = kind, scope = scope, thresholdMiB = minimumMiB,
                        notice = "Scan stopped. No files were changed."
                    )
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
        job = scanTask
        val token = taskCenter.begin(
            if (kind == AnalysisKind.DUPLICATES) BackgroundTaskKind.DUPLICATE_SCAN
            else BackgroundTaskKind.LARGE_FILE_SCAN,
            copyTaskScope(root),
            cancel = { scanTask.cancel() }
        )
        taskToken = token
        scanTask.invokeOnCompletion { cause ->
            taskCenter.complete(
                token,
                success = cause == null && _state.value.complete &&
                    _state.value.kind == kind,
                wasCancelled = cause is CancellationException || scanTask.isCancelled
            )
        }
        scanTask.start()
    }

    fun toggleDuplicate(id: String) {
        val current = _state.value
        if (!current.complete || current.kind != AnalysisKind.DUPLICATES ||
            current.isDeleting || id !in verifiedTargets) return
        val set = current.selectedDuplicateIds.toMutableSet()
        if (!set.add(id)) set.remove(id)
        _state.value = current.copy(selectedDuplicateIds = set)
    }

    fun selectDuplicateGroup(ids: List<String>) {
        val current = _state.value
        if (!current.complete || current.kind != AnalysisKind.DUPLICATES ||
            current.isDeleting || ids.any { it !in verifiedTargets }) return
        val selection = current.selectedDuplicateIds.toMutableSet()
        if (ids.all { it in selection }) selection.removeAll(ids.toSet())
        else selection.addAll(ids)
        _state.value = current.copy(selectedDuplicateIds = selection)
    }

    fun requestDelete(group: DuplicateGroupResult) {
        val current = _state.value
        if (!current.complete || current.kind != AnalysisKind.DUPLICATES ||
            current.isDeleting || current.pendingDeleteIds.isNotEmpty()) return
        val members = group.members.map { it.entry.id }.toSet()
        if (current.duplicateGroups.none {
            it.members.map { member -> member.entry.id }.toSet() == members
        }) return
        val selected = current.selectedDuplicateIds.intersect(members)
            .filter { it in verifiedTargets }.toSet()
        if (selected.isEmpty()) return
        _state.value = current.copy(pendingDeleteIds = selected, deleteNotice = null)
    }

    /** Individual Delete button beside a duplicate; never auto-confirms. */
    fun requestSingleDelete(group: DuplicateGroupResult, id: String) {
        val current = _state.value
        if (!current.complete || current.kind != AnalysisKind.DUPLICATES ||
            current.isDeleting || current.pendingDeleteIds.isNotEmpty()) return
        val groupIds = group.members.map { it.entry.id }.toSet()
        if (id !in groupIds || id !in verifiedTargets) return
        if (current.duplicateGroups.none {
            it.members.map { member -> member.entry.id }.toSet() == groupIds
        }) return
        _state.value = current.copy(pendingDeleteIds = setOf(id), deleteNotice = null)
    }

    fun cancelDeleteConfirmation() {
        if (_state.value.isDeleting) return
        _state.value = _state.value.copy(pendingDeleteIds = emptySet())
    }

    /** Snapshot of confirmed duplicate-delete locations, never exposed as paths in logs. */
    fun pendingDeleteScopes(): List<com.meowmeow.filemanager.model.TaskScope> =
        _state.value.pendingDeleteIds.mapNotNull { verifiedTargets[it] }
            .map { target ->
                copyTaskScope(CopyPlace(target.node, "analysis", emptyList()))
            }

    fun confirmDelete() {
        val current = _state.value
        if (current.isRunning || current.isDeleting || current.pendingDeleteIds.isEmpty()) return
        val selected = current.pendingDeleteIds.mapNotNull { verifiedTargets[it] }
        if (selected.size != current.pendingDeleteIds.size) {
            _state.value = current.copy(
                pendingDeleteIds = emptySet(),
                deleteNotice = "Some selected files are no longer in the scan. Scan again."
            )
            return
        }
        val idsByIdentity = current.pendingDeleteIds.associateBy {
            verifiedTargets.getValue(it).identity
        }
        _state.value = current.copy(
            isDeleting = true,
            pendingDeleteIds = emptySet(),
            deleteSummary = DuplicateDeleteSummary(total = selected.size),
            deleteNotice = null
        )
        var deleteTaskToken = -1L
        val deletionTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val summary = withContext(Dispatchers.IO) {
                    VerifiedDuplicateDeletion(ops).delete(selected) { progress ->
                        withContext(NonCancellable + Dispatchers.Main) {
                            applyDeletionProgress(progress, idsByIdentity)
                            taskCenter.progress(deleteTaskToken, 0, progress.completed.size)
                        }
                    }
                }
                if (summary.failed > 0) {
                    errors.record(ErrorArea.FILE_ANALYSIS,
                        java.io.IOException("One or more duplicate deletions failed"),
                        operation = ErrorOperation.DUPLICATE_DELETE)
                }
                _state.value = _state.value.copy(
                    isDeleting = false,
                    selectedDuplicateIds = emptySet(),
                    deleteNotice = "Deleted ${summary.deleted}; changed/skipped ${summary.changed}; " +
                        "failed ${summary.failed}. Scan again to refresh duplicate groups."
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    isDeleting = false,
                    selectedDuplicateIds = emptySet(),
                    deleteNotice = "Deletion stopped. Completed deletions remain. Scan again."
                )
            } catch (failure: Exception) {
                errors.record(ErrorArea.FILE_ANALYSIS, failure,
                    operation = ErrorOperation.DUPLICATE_DELETE)
                _state.value = _state.value.copy(
                    isDeleting = false,
                    selectedDuplicateIds = emptySet(),
                    deleteNotice = "Deletion stopped. Check remaining files and scan again."
                )
            } finally {
                deleteJob = null
            }
        }
        deleteJob = deletionTask
        val scopes = selected.map { target ->
            copyTaskScope(CopyPlace(target.node, "duplicate", emptyList()))
        }
        val token = taskCenter.begin(
            BackgroundTaskKind.DUPLICATE_DELETE,
            scopes.firstOrNull() ?: com.meowmeow.filemanager.model.TaskScope.Unknown,
            affectedScopes = scopes, readOnly = false,
            cancel = { deletionTask.cancel() }
        )
        deleteTaskToken = token
        deletionTask.invokeOnCompletion { cause ->
            taskCenter.complete(
                token,
                success = cause == null && _state.value.deleteSummary?.failed == 0 &&
                    !_state.value.isDeleting,
                wasCancelled = cause is CancellationException || deletionTask.isCancelled
            )
        }
        deletionTask.start()
    }

    fun cancelDeletion() {
        if (_state.value.isDeleting) deleteJob?.cancel()
    }

    fun dismissDeleteNotice() {
        _state.value = _state.value.copy(deleteNotice = null)
    }

    private fun applyDeletionProgress(
        summary: DuplicateDeleteSummary, idsByIdentity: Map<String, String>
    ) {
        val deleted = summary.completed
            .filter { it.status == DuplicateDeleteStatus.DELETED }
            .mapNotNull { idsByIdentity[it.identity] }.toSet()
        deleted.forEach { verifiedTargets.remove(it) }
        val current = _state.value
        val groups = current.duplicateGroups.mapNotNull { group ->
            val remaining = group.members.filter { it.entry.id !in deleted }
            if (remaining.size < 2) null else group.copy(
                members = remaining,
                reclaimableBytes = group.sizeBytes.coerceAtLeast(0L) *
                    (remaining.size - 1).toLong()
            )
        }
        _state.value = current.copy(
            deleteSummary = summary,
            duplicateGroups = groups,
            selectedDuplicateIds = current.selectedDuplicateIds - deleted,
            reclaimableBytes = groups.sumOf { it.reclaimableBytes }
        )
    }

    class Factory(context: Context, private val taskCenter: TaskCenterViewModel): ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileAnalysisViewModel::class.java))
            return FileAnalysisViewModel(
                AndroidCopyOperations(app), (app as MeowMeowApp).errorLog, taskCenter
            ) as T
        }
    }
}
