package com.meowmeow.filemanager.ui

import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.documentfile.provider.DocumentFile
import com.meowmeow.filemanager.data.CopyNode
import com.meowmeow.filemanager.data.CopyPlace
import com.meowmeow.filemanager.model.TaskScope
import com.meowmeow.filemanager.model.scopesMayOverlap
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class BackgroundTaskKind {
    DEVICE_SEARCH, FOLDER_SEARCH, DUPLICATE_SCAN, LARGE_FILE_SCAN,
    FILE_COPY, BULK_OPERATION, DUPLICATE_DELETE, DOWNLOADS_SCAN, DOWNLOADS_DELETE
}
enum class BackgroundTaskStatus { RUNNING, STOPPING, COMPLETED, STOPPED, FAILED, OUTDATED }

data class BackgroundTask(
    val id: Long,
    val kind: BackgroundTaskKind,
    val scope: TaskScope,
    val label: String,
    val status: BackgroundTaskStatus,
    val affectedScopes: List<TaskScope> = listOf(scope),
    val readOnly: Boolean = true,
    val folders: Int = 0,
    val files: Int = 0,
    val hashed: Int = 0
) {
    val active get() = status == BackgroundTaskStatus.RUNNING ||
        status == BackgroundTaskStatus.STOPPING
}

data class TaskConflict(
    val action: String,
    val conflictingTaskIds: List<Long>,
    val description: String
)

data class TaskCenterState(
    val tasks: List<BackgroundTask> = emptyList(),
    val conflict: TaskConflict? = null,
    val notice: String? = null
) {
    val runningCount: Int get() = tasks.count { it.active }

    fun latestOutdated(vararg kinds: BackgroundTaskKind): Boolean =
        tasks.lastOrNull { it.kind in kinds }?.status == BackgroundTaskStatus.OUTDATED
}

/** Tasks outlive individual tabs but not the Android app process. */
class TaskCenterViewModel : ViewModel() {
    private val _state = MutableStateFlow(TaskCenterState())
    val state: StateFlow<TaskCenterState> = _state
    private val cancellation = mutableMapOf<Long, () -> Unit>()
    private var sequence = 0L

    @Synchronized
    fun begin(
        kind: BackgroundTaskKind, scope: TaskScope,
        affectedScopes: List<TaskScope> = listOf(scope),
        readOnly: Boolean = true,
        cancel: () -> Unit
    ): Long {
        val id = ++sequence
        cancellation[id] = cancel
        val previous = _state.value.tasks
        _state.value = _state.value.copy(
            tasks = (previous + BackgroundTask(
                id, kind, scope, title(kind), BackgroundTaskStatus.RUNNING,
                affectedScopes = affectedScopes, readOnly = readOnly
            )).takeLast(20),
            notice = null
        )
        return id
    }

    @Synchronized
    fun progress(id: Long, folders: Int, files: Int, hashed: Int = 0) {
        _state.value = _state.value.copy(tasks = _state.value.tasks.map {
            if (it.id == id && it.active) it.copy(folders = folders, files = files, hashed = hashed)
            else it
        })
    }

    @Synchronized
    fun complete(id: Long, success: Boolean, wasCancelled: Boolean) {
        cancellation.remove(id)
        _state.value = _state.value.copy(tasks = _state.value.tasks.map {
            if (it.id != id) it
            else it.copy(status = when {
                wasCancelled || it.status == BackgroundTaskStatus.STOPPING ->
                    BackgroundTaskStatus.STOPPED
                success -> BackgroundTaskStatus.COMPLETED
                else -> BackgroundTaskStatus.FAILED
            })
        })
    }

    /** Never queues or retries the requested mutation. */
    @Synchronized
    fun allowMutation(action: String, locations: List<TaskScope>?): Boolean {
        val blocking = _state.value.tasks.filter { task ->
            task.active && (locations.isNullOrEmpty() ||
                locations.any { location ->
                    task.affectedScopes.any { scopesMayOverlap(it, location) }
                })
        }
        if (blocking.isEmpty()) return true
        _state.value = _state.value.copy(
            conflict = TaskConflict(action, blocking.map { it.id },
                if (locations.isNullOrEmpty())
                    "Storage access may change while tasks are reading files."
                else "An active task may be using files affected by this action.")
        )
        return false
    }

    /** Read-only tasks can overlap other scans, but not an active writer. */
    @Synchronized
    fun allowScan(action: String, location: TaskScope): Boolean {
        val blocking = _state.value.tasks.filter { task ->
            task.active && !task.readOnly &&
                task.affectedScopes.any { scopesMayOverlap(it, location) }
        }
        if (blocking.isEmpty()) return true
        _state.value = _state.value.copy(conflict = TaskConflict(
            action, blocking.map { it.id },
            "A file-changing task may be modifying the requested scan location."
        ))
        return false
    }

    @Synchronized
    fun keepCurrentTasks() {
        _state.value = _state.value.copy(conflict = null)
    }

    /** Stop the existing tasks only. The new action is deliberately discarded. */
    fun stopConflictingTasks() {
        val ids = synchronized(this) {
            val pending = _state.value.conflict?.conflictingTaskIds.orEmpty()
            _state.value = _state.value.copy(
                conflict = null,
                notice = "Stopping conflicting tasks. Try the action again after they stop."
            )
            pending
        }
        ids.forEach(::stop)
    }

    fun stop(id: Long) {
        val cancel = synchronized(this) {
            val item = _state.value.tasks.firstOrNull { it.id == id } ?: return
            if (!item.active || item.status == BackgroundTaskStatus.STOPPING) return
            _state.value = _state.value.copy(tasks = _state.value.tasks.map {
                if (it.id == id) it.copy(status = BackgroundTaskStatus.STOPPING) else it
            })
            cancellation[id]
        }
        cancel?.invoke()
    }

    /** Mark completed results outdated only for files actually affected. */
    @Synchronized
    fun noteChanged(locations: List<TaskScope>?) {
        _state.value = _state.value.copy(tasks = _state.value.tasks.map { task ->
            if (task.readOnly && (task.status == BackgroundTaskStatus.COMPLETED ||
                task.status == BackgroundTaskStatus.OUTDATED) &&
                (locations.isNullOrEmpty() || locations.any {
                    scopesMayOverlap(task.scope, it)
                })
            ) task.copy(status = BackgroundTaskStatus.OUTDATED)
            else task
        })
    }

    @Synchronized
    fun dismiss(id: Long) {
        if (_state.value.tasks.any { it.id == id && it.active }) return
        _state.value = _state.value.copy(tasks = _state.value.tasks.filter { it.id != id })
    }

    @Synchronized
    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    /** An external permission revocation is not an app-initiated tab switch. */
    fun accessRevoked() {
        val ids = synchronized(this) {
            _state.value = _state.value.copy(
                notice = "Storage permission changed. Affected tasks must be run again."
            )
            _state.value.tasks.filter { it.active }.map { it.id }
        }
        ids.forEach(::stop)
        noteChanged(null)
    }

    private fun title(kind: BackgroundTaskKind) = when (kind) {
        BackgroundTaskKind.DEVICE_SEARCH -> "Device file search"
        BackgroundTaskKind.FOLDER_SEARCH -> "Folder search"
        BackgroundTaskKind.DUPLICATE_SCAN -> "Duplicate scan"
        BackgroundTaskKind.LARGE_FILE_SCAN -> "Large-file scan"
        BackgroundTaskKind.FILE_COPY -> "Copy files"
        BackgroundTaskKind.BULK_OPERATION -> "Bulk file operation"
        BackgroundTaskKind.DUPLICATE_DELETE -> "Delete duplicates"
        BackgroundTaskKind.DOWNLOADS_SCAN -> "Downloads scan"
        BackgroundTaskKind.DOWNLOADS_DELETE -> "Downloads cleanup"
    }
}

fun deviceTaskScope(file: File): TaskScope =
    TaskScope.Device(file.absoluteFile.normalize().path)

fun scopedTaskScope(doc: DocumentFile): TaskScope = try {
    val uri = doc.uri
    TaskScope.Saf(uri.authority ?: "", DocumentsContract.getTreeDocumentId(uri))
} catch (_: Exception) {
    TaskScope.Unknown
}

fun copyTaskScope(place: CopyPlace?): TaskScope = when (val node = place?.node) {
    is CopyNode.Device -> deviceTaskScope(node.file)
    is CopyNode.Scoped -> scopedTaskScope(node.file)
    else -> TaskScope.Unknown
}
