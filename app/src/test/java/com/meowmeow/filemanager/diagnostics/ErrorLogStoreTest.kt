package com.meowmeow.filemanager.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorLogStoreTest {
    @Test fun categorizationDoesNotLeakPrivatePaths() {
        val original = SecurityException("/storage/emulated/0/Downloads/private.pdf")
        assertEquals("Permission denied", errorCategory(original))
        val report = formatErrorLog(listOf(
            ErrorEntry(1000L, ErrorArea.DEVICE_BROWSING, errorCategory(original), false)
        ))
        assertFalse(report.contains("private.pdf"))
        assertFalse(report.contains("/storage/emulated"))
        assertTrue(report.contains("DEVICE BROWSING"))
    }

    @Test fun storageErrorsDoNotIncludeRawMessage() {
        assertEquals("Storage I/O error", errorCategory(IOException("Sensitive filename")))
    }

    @Test fun emptyLogExplainsItsState() {
        assertTrue(formatErrorLog(emptyList()).contains("No errors recorded."))
    }
}
