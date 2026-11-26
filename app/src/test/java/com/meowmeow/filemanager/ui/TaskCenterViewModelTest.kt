package com.meowmeow.filemanager.ui

import com.meowmeow.filemanager.model.TaskScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCenterViewModelTest {
    private val downloads = TaskScope.Device("/storage/emulated/0/Download")
    private val pictures = TaskScope.Device("/storage/emulated/0/Pictures")

    @Test fun readOnlyTaskSurvivesUnrelatedMutations() {
        val tasks = TaskCenterViewModel()
        val id = tasks.begin(BackgroundTaskKind.DUPLICATE_SCAN, downloads) {}
        tasks.progress(id, 4, 250, 80)
        assertTrue(tasks.allowMutation("Delete an unrelated picture", listOf(pictures)))
        assertEquals(1, tasks.state.value.runningCount)
        assertEquals(250, tasks.state.value.tasks.single().files)
    }

    @Test fun conflictNeverQueuesOrAutomaticallyExecutesMutation() {
        val tasks = TaskCenterViewModel()
        var cancellationRequests = 0
        val id = tasks.begin(BackgroundTaskKind.DUPLICATE_SCAN, downloads) {
            cancellationRequests++
        }
        assertFalse(tasks.allowMutation(
            "Delete Downloads", listOf(TaskScope.Device("/storage/emulated/0/Download/folder"))
        ))
        assertEquals(1, tasks.state.value.conflict?.conflictingTaskIds?.size)
        tasks.stopConflictingTasks()
        assertEquals(1, cancellationRequests)
        assertNull(tasks.state.value.conflict)
        assertEquals(BackgroundTaskStatus.STOPPING, tasks.state.value.tasks.single().status)
        // Job has not finished releasing handles. No mutation can yet proceed.
        assertFalse(tasks.allowMutation("Delete Downloads", listOf(downloads)))
        tasks.keepCurrentTasks()
        tasks.complete(id, success = false, wasCancelled = true)
        assertEquals(BackgroundTaskStatus.STOPPED, tasks.state.value.tasks.single().status)
        // Caller must explicitly ask again; manager stores no new-action callback.
        assertTrue(tasks.allowMutation("Delete Downloads", listOf(downloads)))
    }

    @Test fun keepingExistingTaskDiscardsOnlyNewRequest() {
        val tasks = TaskCenterViewModel()
        var stopped = false
        tasks.begin(BackgroundTaskKind.DEVICE_SEARCH, downloads) { stopped = true }
        assertFalse(tasks.allowMutation("Rename folder", listOf(downloads)))
        tasks.keepCurrentTasks()
        assertNull(tasks.state.value.conflict)
        assertFalse(stopped)
        assertEquals(1, tasks.state.value.runningCount)
    }

    @Test fun unknownAndPermissionChangesAlwaysWarn() {
        val tasks = TaskCenterViewModel()
        tasks.begin(BackgroundTaskKind.FOLDER_SEARCH, TaskScope.Saf("documents", "opaque")) {}
        assertFalse(tasks.allowMutation("Change permissions", null))
        tasks.keepCurrentTasks()
        assertFalse(tasks.allowMutation("Delete unknown file", listOf(TaskScope.Unknown)))
    }

    @Test fun changedCompletedTaskBecomesOutdatedWithoutRescanning() {
        val tasks = TaskCenterViewModel()
        val id = tasks.begin(BackgroundTaskKind.DEVICE_SEARCH, downloads) {}
        tasks.complete(id, success = true, wasCancelled = false)
        tasks.noteChanged(listOf(pictures))
        assertEquals(BackgroundTaskStatus.COMPLETED, tasks.state.value.tasks.single().status)
        tasks.noteChanged(listOf(TaskScope.Device("/storage/emulated/0/Download/test.txt")))
        assertTrue(tasks.state.value.latestOutdated(BackgroundTaskKind.DEVICE_SEARCH))
        assertEquals(BackgroundTaskStatus.OUTDATED, tasks.state.value.tasks.single().status)
    }

    @Test fun externallyRevokedSafGrantStopsOnlyAffectedTasks() {
        val tasks = TaskCenterViewModel()
        var stoppedSaf = false
        var stoppedDevice = false
        val saf = tasks.begin(
            BackgroundTaskKind.FOLDER_SEARCH, TaskScope.Saf("documents", "tree-1")
        ) { stoppedSaf = true }
        tasks.begin(BackgroundTaskKind.DEVICE_SEARCH, downloads) {
            stoppedDevice = true
        }
        tasks.reconcileSafPermissions(emptySet())
        assertTrue(stoppedSaf)
        assertFalse(stoppedDevice)
        assertEquals(BackgroundTaskStatus.STOPPING,
            tasks.state.value.tasks.first { it.id == saf }.status)
        assertTrue(tasks.state.value.notice?.contains("revoked") == true)
    }

    @Test fun persistedSafGrantKeepsSearchRunning() {
        val tasks = TaskCenterViewModel()
        var stopped = false
        tasks.begin(BackgroundTaskKind.FOLDER_SEARCH,
            TaskScope.Saf("documents", "tree-1")) { stopped = true }
        tasks.reconcileSafPermissions(setOf("documents" to "tree-1"))
        assertFalse(stopped)
        assertEquals(1, tasks.state.value.runningCount)
    }

    @Test fun externalPermissionLossStopsAllRunningTasks() {
        val tasks = TaskCenterViewModel()
        var stops = 0
        val a = tasks.begin(BackgroundTaskKind.DEVICE_SEARCH, downloads) { stops++ }
        val b = tasks.begin(BackgroundTaskKind.DUPLICATE_SCAN, pictures) { stops++ }
        tasks.accessRevoked()
        assertEquals(2, stops)
        assertEquals(2, tasks.state.value.runningCount) // still STOPPING
        tasks.complete(a, false, true)
        tasks.complete(b, false, true)
        assertEquals(0, tasks.state.value.runningCount)
        assertTrue(tasks.state.value.notice?.contains("permission") == true)
    }
}
