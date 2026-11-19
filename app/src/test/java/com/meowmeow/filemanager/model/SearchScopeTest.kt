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

    @Test fun inaccessibleStartingScopeReturnsNullInsteadOfFallingBack() {
        assertNull(searchStartFolder("storage", null, SearchScope.CURRENT_AND_SUBFOLDERS))
        assertNull(searchStartFolder(null, "photos", SearchScope.ROOT_AND_SUBFOLDERS))
    }
}
