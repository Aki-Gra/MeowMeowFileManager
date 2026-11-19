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
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CopyUiState(
    val sourceName: String? = null,
    val isCopying: Boolean = false,
    val filesCopied: Int = 0,
    val foldersCopied: Int = 0,
    val bytesCopied: Long = 0,
    val notice: String? = null
)

/** In-memory, single-item clipboard. Never persists file paths, handles or contents. */
class CopyViewModel(
    private val copyOps: AndroidCopyOperations,
    private val errorLog: ErrorLogStore
) : ViewModel() {
    private val _state = MutableStateFlow(CopyUiState())
    val state: StateFlow<CopyUiState> = _state
    private var selected: CopyPlace? = null
    private var job: Job? = null

    fun select(place: CopyPlace?, displayName: String) {
        if (_state.value.isCopying) return
        if (place == null) {
            _state.value = _state.value.copy(notice = "Unable to select this item for copying.")
            return
        }
        selected = place
        _state.value = CopyUiState(sourceName = displayName)
    }

    fun paste(destination: CopyPlace?, onSuccess: () -> Unit) {
        if (_state.value.isCopying) return
        val source = selected ?: return
        if (destination == null) {
            _state.value = _state.value.copy(notice = "Open a writable folder first.")
            return
        }
        // Mark the operation as started before dispatching the coroutine, so
        // rapid double-taps cannot start two copies of the same item.
        _state.value = _state.value.copy(
            isCopying = true, filesCopied = 0, foldersCopied = 0,
            bytesCopied = 0, notice = null
        )
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val isFolder = copyOps.isDirectory(source.node)
                    validateCopyDestination(source, destination, isFolder)
                    SafeCopy(copyOps).perform(source.node, destination.node) { totals ->
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
                    notice = "Copied " + result.copiedFiles + " file(s), " +
                        result.copiedFolders + " folder(s)."
                )
                onSuccess()
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    isCopying = false, notice = "Copy cancelled. Partial copies were cleaned up if possible."
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
                _state.value = _state.value.copy(isCopying = false, notice = message)
            } finally {
                job = null
            }
        }
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

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CopyViewModel::class.java))
            return CopyViewModel(
                AndroidCopyOperations(app),
                (app as MeowMeowApp).errorLog
            ) as T
        }
    }
}
