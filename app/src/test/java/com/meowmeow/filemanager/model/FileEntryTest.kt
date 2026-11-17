package com.meowmeow.filemanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEntryTest {
    private fun item(name: String, dir: Boolean = false, size: Long = 0, modified: Long = 0) =
        FileEntry(name, name, dir, size, modified, null, true)

    @Test fun foldersAlwaysComeFirst() {
        val input = listOf(item("z.txt"), item("docs", dir = true), item("a.txt"))
        assertEquals(listOf("docs", "a.txt", "z.txt"), sortFiles(input, SortMode.NAME).map { it.name })
    }

    @Test fun sizeSortDescendingWithinFiles() {
        val input = listOf(item("small", size = 12), item("large", size = 44))
        assertEquals(listOf("large", "small"), sortFiles(input, SortMode.SIZE).map { it.name })
    }

    @Test fun dateSortDescending() {
        val input = listOf(item("older", modified = 1), item("newer", modified = 200))
        assertEquals(listOf("newer", "older"), sortFiles(input, SortMode.MODIFIED).map { it.name })
    }

    @Test fun invalidNamesRejected() {
        assertFalse(isValidFileName(""))
        assertFalse(isValidFileName("../secret"))
        assertFalse(isValidFileName("hello/there"))
        assertFalse(isValidFileName(" leading"))
        assertTrue(isValidFileName("My Documents"))
    }
}
