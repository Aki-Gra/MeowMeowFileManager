package com.meowmeow.filemanager.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerViewModeTest {
    @Test fun oldInstallDefaultsToList() {
        assertEquals(ExplorerViewMode.LIST, ExplorerViewMode.fromStored(null))
        assertEquals(ExplorerViewMode.LIST, ExplorerViewMode.fromStored("UNKNOWN"))
        assertEquals(ExplorerViewMode.LIST, ExplorerViewMode.fromStored(""))
    }

    @Test fun allModesRoundTrip() {
        ExplorerViewMode.entries.forEach {
            assertEquals(it, ExplorerViewMode.fromStored(it.name))
        }
    }
}
