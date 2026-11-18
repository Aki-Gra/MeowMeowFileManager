package com.meowmeow.filemanager.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Date

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

    @Test fun testEntryIsExplicitlyMarked() {
        val report = formatErrorLog(listOf(
            ErrorEntry(1000L, ErrorArea.DIAGNOSTICS_TEST, "Test entry", false)
        ))
        assertTrue(report.contains("TEST | DIAGNOSTICS TEST | Test entry"))
    }

    @Test fun suggestedExportFilenameIsSafeAndText() {
        val fileName = suggestedErrorLogFileName(Date(0L))
        assertTrue(fileName.startsWith("MeowMeow-error-log-"))
        assertTrue(fileName.endsWith(".txt"))
        assertFalse(fileName.contains(":"))
        assertFalse(fileName.contains("/"))
    }

    @Test fun stableOperationCodesDoNotContainExceptionMessages() {
        val error = IllegalStateException("Private /storage/emulated/0/secret.txt")
        val entry = ErrorEntry(
            12345L, ErrorArea.DEVICE_FILE_OPERATION, errorCategory(error),
            false, ErrorOperation.FS_RENAME
        )
        assertEquals("MM-FS-REN-04", errorCode(entry))
        val report = formatErrorLog(listOf(entry))
        assertTrue(report.contains("MM-FS-REN-04 | Rename device item"))
        assertFalse(report.contains("secret.txt"))
    }

    @Test fun oldEntriesRemainClearlyUnspecified() {
        val old = ErrorEntry(12345L, ErrorArea.DEVICE_FILE_OPERATION, "Operation unavailable", false)
        assertEquals("MM-OLD-04", errorCode(old))
        assertTrue(formatErrorLog(listOf(old)).contains("Unspecified (older entry)"))
    }

    @Test fun distinguishesCreateAndDeleteErrors() {
        val create = ErrorEntry(1L, ErrorArea.DEVICE_FILE_OPERATION,
            "Operation unavailable", false, ErrorOperation.FS_CREATE)
        val delete = create.copy(operation = ErrorOperation.FS_DELETE)
        assertEquals("MM-FS-MKDIR-04", errorCode(create))
        assertEquals("MM-FS-DEL-04", errorCode(delete))
    }

    @Test fun emptyLogExplainsItsState() {
        assertTrue(formatErrorLog(emptyList()).contains("No errors recorded."))
    }
}
