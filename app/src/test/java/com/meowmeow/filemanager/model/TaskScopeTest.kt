package com.meowmeow.filemanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScopeTest {
    @Test fun unrelatedDeviceFoldersCanBeChangedConcurrently() {
        val scan = TaskScope.Device("/storage/emulated/0/Downloads")
        assertFalse(scopesMayOverlap(scan, TaskScope.Device("/storage/emulated/0/Pictures/a.jpg")))
    }

    @Test fun nestedAndParentPathsConflictInBothDirections() {
        val root = TaskScope.Device("/storage/emulated/0/Downloads")
        val child = TaskScope.Device("/storage/emulated/0/Downloads/notes/a.txt")
        assertTrue(scopesMayOverlap(root, child))
        assertTrue(scopesMayOverlap(child, root))
    }

    @Test fun componentBoundaryPreventsFalseDeviceConflict() {
        assertFalse(scopesMayOverlap(TaskScope.Device("/home/a"),
            TaskScope.Device("/home/a2/file")))
    }

    @Test fun safIdsAreConservativelyTreatedAsPotentiallyOverlapping() {
        assertTrue(scopesMayOverlap(TaskScope.Saf("provider", "tree-one"),
            TaskScope.Saf("provider", "tree-two")))
        assertTrue(scopesMayOverlap(TaskScope.Saf("provider", "tree-one"),
            TaskScope.Device("/storage/emulated/0/Downloads")))
        assertTrue(scopesMayOverlap(TaskScope.Unknown,
            TaskScope.Device("/storage/emulated/0/Downloads")))
    }
}
