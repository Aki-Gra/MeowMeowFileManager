package com.meowmeow.filemanager.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.data.AndroidCopyOperations
import com.meowmeow.filemanager.data.CopyNode
import com.meowmeow.filemanager.data.CopyPlace
import com.meowmeow.filemanager.data.validateCopyDestination
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import com.meowmeow.filemanager.model.ExpectedFileOperationException
import com.meowmeow.filemanager.model.SafeCopy
import com.meowmeow.filemanager.model.CopyConflict
import com.meowmeow.filemanager.model.CopyConflictChoice
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CopyUiState(
    val sourceName: String? = null,
    val conflict: CopyConflict? = null,
    val isCopying: Boolean = false,
    val filesCopied: Int = 0,
    val foldersCopied: Int = 0,
    val bytesCopied: Long = 0,
    val notice: String? = null
)

/** In-memory, single-item clipboard. Never persists file paths, handles or contents. */
class CopyViewModel(
    private val copyOps: AndroidCopyOperations,
    private val errorLog: ErrorLogStore,
    private val taskCenter: TaskCenterViewModel
) : ViewModel() {
    private val _state = MutableStateFlow(CopyUiState())
    val state: StateFlow<CopyUiState> = _state
    private var selected: CopyPlace? = null
    private var job: Job? = null
    private var conflictAnswer: CompletableDeferred<CopyConflictChoice>? = null

    private suspend fun askConflict(conflict: CopyConflict): CopyConflictChoice =
        withContext(Dispatchers.Main) {
            val answer = CompletableDeferred<CopyConflictChoice>()
            conflictAnswer = answer
            _state.value = _state.value.copy(conflict = conflict)
            try {
                answer.await()
            } finally {
                conflictAnswer = null
                _state.value = _state.value.copy(conflict = null)
            }
        }

    fun answerConflict(choice: CopyConflictChoice) {
        conflictAnswer?.complete(choice)
    }

    fun select(place: CopyPlace?, displayName: String) {
        if (_state.value.isCopying) return
        if (place == null) {
            _state.value = _state.value.copy(notice = "Unable to select this item for copying.")
            return
        }
        selected = place
        _state.value = CopyUiState(sourceName = displayName)
    }

    fun pendingSource(): CopyPlace? = selected

    fun paste(destination: CopyPlace?, onSuccess: () -> Unit) {
        if (_state.value.isCopying) return
        val source = selected ?: return
        if (destination == null) {
            _state.value = _state.value.copy(notice = "Open a writable folder first.")
            return
        }
        if (!taskCenter.allowCopy("Copy files", listOf(copyTaskScope(source)),
                copyTaskScope(destination))) return
        // Mark the operation as started before dispatching the coroutine, so
        // rapid double-taps cannot start two copies of the same item.
        _state.value = _state.value.copy(
            isCopying = true, filesCopied = 0, foldersCopied = 0,
            bytesCopied = 0, notice = null
        )
        val copyTask = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(Dispatchers.IO) {
                    copyOps.validateSearchSource(source)
                    val isFolder = copyOps.isDirectory(source.node)
                    validateCopyDestination(source, destination, isFolder)
                    SafeCopy(copyOps).perform(
                        source.node, destination.node,
                        resolveConflict = { conflict -> askConflict(conflict) }
                    ) { totals ->
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(
                                filesCopied = totals.copiedFiles,
                                foldersCopied = totals.copiedFolders,
                                bytesCopied = totals.bytes
                            )
                        }
                    }
                }
                selected = null
                _state.value = CopyUiState(
                    notice = when {
                        result.replacedExisting -> "Existing file replaced after verification."
                        result.renamedTo != null -> "Copy saved under the new name."
                        else -> "Copied " + result.copiedFiles + " file(s), " +
                            result.copiedFolders + " folder(s)."
                    }
                )
                onSuccess()
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    isCopying = false, conflict = null,
                    notice = "Copy cancelled. Partial copies were cleaned up if possible."
                )
            } catch (failure: Exception) {
                val code = when (source.node) {
                    is CopyNode.Device -> if (destination.node is CopyNode.Scoped)
                        ErrorOperation.COPY_BETWEEN else ErrorOperation.FS_COPY
                    is CopyNode.Scoped -> if (destination.node is CopyNode.Device)
                        ErrorOperation.COPY_BETWEEN else ErrorOperation.SAF_COPY
                }
                errorLog.record(ErrorArea.COPY_OPERATION, failure, operation = code)
                val message = when (failure) {
                    is ExpectedFileOperationException ->
                        failure.message ?: "Copy cannot continue here."
                    is IOException -> if (failure.message?.startsWith("Incomplete copy") == true)
                        "Incomplete copy may remain in destination. Review it before retrying."
                        else "Copy failed. Source files were not changed."
                    else -> "Copy failed. Source files were not changed."
                }
                _state.value = _state.value.copy(
                    isCopying = false, conflict = null, notice = message
                )
            } finally {
                job = null
            }
        }
        job = copyTask
        val token = taskCenter.begin(
            BackgroundTaskKind.FILE_COPY, copyTaskScope(destination),
            affectedScopes = listOf(copyTaskScope(source), copyTaskScope(destination)),
            readOnly = false, cancel = { copyTask.cancel() }
        )
        copyTask.invokeOnCompletion { cause ->
            taskCenter.noteChanged(listOf(copyTaskScope(destination)))
            taskCenter.complete(
                token, success = cause == null && selected == null,
                wasCancelled = cause is CancellationException || copyTask.isCancelled
            )
        }
        copyTask.start()
    }

    fun cancelOrClear() {
        if (_state.value.isCopying) {
            job?.cancel()
            _state.value = _state.value.copy(
                notice = "Cancelling and cleaning up the incomplete copy..."
            )
        } else {
            selected = null
            _state.value = CopyUiState()
        }
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    class Factory(context: Context, private val taskCenter: TaskCenterViewModel) : ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CopyViewModel::class.java))
            return CopyViewModel(
                AndroidCopyOperations(app),
                (app as MeowMeowApp).errorLog, taskCenter
            ) as T
        }
    }
}
