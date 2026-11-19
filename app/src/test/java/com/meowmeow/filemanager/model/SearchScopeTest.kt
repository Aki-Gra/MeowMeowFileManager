package com.meowmeow.filemanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScopeTest {
    @Test fun currentFolderOnlyUsesCurrentDirectoryWithoutRecursing() {
        assertEquals("photos", searchStartFolder("storage", "photos", SearchScope.CURRENT_FOLDER))
        assertFalse(SearchScope.CURRENT_FOLDER.recursive)
    }

    @Test fun currentWithSubfoldersStartsAtCurrentDirectoryNotRoot() {
        assertEquals("photos", searchStartFolder("storage", "photos", SearchScope.CURRENT_AND_SUBFOLDERS))
        assertTrue(SearchScope.CURRENT_AND_SUBFOLDERS.recursive)
    }

    @Test fun rootWithSubfoldersStartsAtRootEvenWhenBrowsingDeeper() {
        assertEquals("storage", searchStartFolder("storage", "photos", SearchScope.ROOT_AND_SUBFOLDERS))
        assertTrue(SearchScope.ROOT_AND_SUBFOLDERS.recursive)
    }

    @Test fun allThreeScopesReturnExpectedSubtrees() = kotlinx.coroutines.runBlocking {
        fun file(name: String, directory: Boolean = false) =
            FileEntry(name, name, directory, 1L, 0L, null, true)
        val tree = mapOf(
            "root" to listOf(file("current", true) to "current", file("sibling", true) to "sibling"),
            "current" to listOf(file("current.txt") to "current.txt", file("inner", true) to "inner"),
            "inner" to listOf(file("inside.txt") to "inside.txt"),
            "sibling" to listOf(file("sibling.txt") to "sibling.txt")
        )
        val scanner = FileDiscoveryScanner<String>({ it }, { folder -> tree[folder].orEmpty() })
        suspend fun matched(scope: SearchScope): Set<String> {
            val start = requireNotNull(searchStartFolder("root", "current", scope))
            return scanner.scan(start, scope.recursive, ".txt", FileKind.ALL)
                .matches.map { it.entry.name }.toSet()
        }
        assertEquals(setOf("current.txt"), matched(SearchScope.CURRENT_FOLDER))
        assertEquals(setOf("current.txt", "inside.txt"),
            matched(SearchScope.CURRENT_AND_SUBFOLDERS))
        assertEquals(setOf("current.txt", "inside.txt", "sibling.txt"),
            matched(SearchScope.ROOT_AND_SUBFOLDERS))
    }

    @Test fun inaccessibleStartingScopeReturnsNullInsteadOfFallingBack() {
        assertNull(searchStartFolder("storage", null, SearchScope.CURRENT_AND_SUBFOLDERS))
        assertNull(searchStartFolder(null, "photos", SearchScope.ROOT_AND_SUBFOLDERS))
    }
}
