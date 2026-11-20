package com.meowmeow.filemanager.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.data.AndroidCopyOperations
import com.meowmeow.filemanager.data.CopyPlace
import com.meowmeow.filemanager.data.validateCopyDestination
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorLogStore
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.model.BulkAction
import com.meowmeow.filemanager.model.BulkProgress
import com.meowmeow.filemanager.model.BulkSelection
import com.meowmeow.filemanager.model.BulkStatus
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SafeBulkOperations
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BulkStage { OFF, SELECTING, DESTINATION, REVIEW, RUNNING, RESULTS }

data class BulkUiState(
    val stage: BulkStage = BulkStage.OFF,
    val selectedIds: Set<String> = emptySet(),
    val selectedNames: List<String> = emptyList(),
    val selectedBytes: Long = 0L,
    val action: BulkAction? = null,
    val destinationLabel: String? = null,
    val progress: BulkProgress? = null,
    val notice: String? = null,
    val cancelled: Boolean = false
) {
    val count: Int get() = selectedIds.size
}

/** In-memory multi-selection, never persisted to preferences or diagnostics. */
class BulkViewModel(
    private val operations: AndroidCopyOperations,
    private val errorLog: ErrorLogStore
) : ViewModel() {
    private val chosen = linkedMapOf<String, Pair<CopyPlace, FileEntry>>()
    private val _state = MutableStateFlow(BulkUiState())
    val state: StateFlow<BulkUiState> = _state
    private var job: Job? = null
    private var destination: CopyPlace? = null

    fun startSelection() {
        if (_state.value.stage != BulkStage.OFF) return
        chosen.clear()
        _state.value = BulkUiState(stage = BulkStage.SELECTING)
    }

    fun toggle(entry: FileEntry, place: CopyPlace?) {
        if (_state.value.stage != BulkStage.SELECTING) return
        if (chosen.containsKey(entry.id)) chosen.remove(entry.id)
        else {
            if (chosen.size >= 500) {
                _state.value = _state.value.copy(notice = "Limit: 500 items per operation.")
                return
            }
            if (place == null) {
                _state.value = _state.value.copy(notice = "Item no longer accessible.")
                return
            }
            chosen[entry.id] = place to entry
        }
        publishSelected()
    }

    fun selectAll(entries: List<FileEntry>, resolve: (FileEntry) -> CopyPlace?) {
        if (_state.value.stage != BulkStage.SELECTING) return
        for (entry in entries.take(500)) {
            val place = resolve(entry) ?: continue
            chosen[entry.id] = place to entry
        }
        publishSelected()
        if (entries.size > 500) {
            _state.value = _state.value.copy(notice = "Selected first 500 items.")
        }
    }

    fun clearSelection() {
        if (_state.value.stage != BulkStage.SELECTING) return
        chosen.clear()
        publishSelected()
    }

    fun leaveSelection() {
        if (_state.value.stage == BulkStage.SELECTING) abandon()
    }

    fun abandon() {
        if (_state.value.stage == BulkStage.RUNNING) return
        chosen.clear()
        destination = null
        _state.value = BulkUiState()
    }

    private fun publishSelected() {
        _state.value = _state.value.copy(
            selectedIds = chosen.keys.toSet(),
            selectedNames = chosen.values.map { it.second.name },
            selectedBytes = chosen.values.sumOf {
                if (it.second.isDirectory) 0L else it.second.sizeBytes.coerceAtLeast(0L)
            },
            notice = null
        )
    }

    fun chooseAction(action: BulkAction) {
        if (_state.value.stage != BulkStage.SELECTING || chosen.isEmpty()) return
        destination = null
        _state.value = _state.value.copy(
            action = action, notice = null,
            stage = if (action == BulkAction.DELETE) BulkStage.REVIEW else BulkStage.DESTINATION
        )
    }

    fun reviewDestination(place: CopyPlace?, label: String) {
        if (_state.value.stage != BulkStage.DESTINATION) return
        if (place == null) {
            _state.value = _state.value.copy(notice = "Choose a writable destination folder.")
            return
        }
        destination = place
        _state.value = _state.value.copy(
            stage = BulkStage.REVIEW, destinationLabel = label, notice = null
        )
    }

    fun backToDestination() {
        if (_state.value.stage != BulkStage.REVIEW) return
        _state.value = _state.value.copy(
            stage = if (_state.value.action == BulkAction.DELETE)
                BulkStage.SELECTING else BulkStage.DESTINATION,
            notice = null
        )
    }

    fun confirm(onFinished: () -> Unit) {
        if (_state.value.stage != BulkStage.REVIEW) return
        val action = _state.value.action ?: return
        val sources = chosen.values.map { (place, entry) ->
            BulkSelection(place.node, entry.name)
        }
        if (sources.isEmpty()) return
        val target = destination
        if (action != BulkAction.DELETE && target == null) return
        _state.value = _state.value.copy(
            stage = BulkStage.RUNNING, progress = BulkProgress(total = sources.size),
            notice = null, cancelled = false
        )
        job = viewModelScope.launch {
            try {
                val completed = withContext(Dispatchers.IO) {
                    SafeBulkOperations(operations).execute(
                        action, sources, target?.node,
                        validateDestination = { source, _ ->
                            val sourcePlace = chosen.values.firstOrNull {
                                it.first.node == source
                            }?.first ?: throw IOException("Source selection is no longer available.")
                            validateCopyDestination(
                                sourcePlace, requireNotNull(target),
                                operations.isDirectory(source)
                            )
                        },
                        onUpdate = { progress ->
                            withContext(Dispatchers.Main) {
                                _state.value = _state.value.copy(progress = progress)
                            }
                        }
                    )
                }
                if (completed.results.any { it.status == BulkStatus.FAILED })
                    recordFailedAction(action)
                _state.value = _state.value.copy(
                    stage = BulkStage.RESULTS, progress = completed, cancelled = false
                )
            } catch (_: CancellationException) {
                _state.value = _state.value.copy(
                    stage = BulkStage.RESULTS, cancelled = true,
                    notice = "Cancelled. Completed changes remain. Review results."
                )
            } catch (_: Exception) {
                recordFailedAction(action)
                _state.value = _state.value.copy(
                    stage = BulkStage.RESULTS,
                    notice = "Operation stopped. Review both locations before retrying."
                )
            } finally {
                job = null
                onFinished()
            }
        }
    }

    private fun recordFailedAction(action: BulkAction) {
        val op = when (action) {
            BulkAction.COPY -> ErrorOperation.BULK_COPY
            BulkAction.MOVE -> ErrorOperation.BULK_MOVE
            BulkAction.DELETE -> ErrorOperation.BULK_DELETE
        }
        errorLog.record(ErrorArea.COPY_OPERATION,
            IOException("Bulk operation encountered failed items"), operation = op)
    }

    fun cancelRunning() {
        if (_state.value.stage != BulkStage.RUNNING) return
        _state.value = _state.value.copy(notice = "Cancelling at the next safe point...")
        job?.cancel()
    }

    fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    class Factory(context: Context): ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T: ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BulkViewModel::class.java))
            return BulkViewModel(
                AndroidCopyOperations(app),
                (app as MeowMeowApp).errorLog
            ) as T
        }
    }
}
