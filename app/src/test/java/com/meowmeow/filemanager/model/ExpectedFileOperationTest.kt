package com.meowmeow.filemanager.model

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ExpectedFileOperationTest {
    @Test fun duplicateNameIsValidationNotLoggable() {
        val error = assertThrows(ExpectedFileOperationException::class.java) {
            rejectExistingName(true)
        }
        assertEquals("An item with that name already exists.", error.message)
        assertFalse(shouldRecordOperationError(error))
    }

    @Test fun blankNameIsValidationNotLoggable() {
        val error = assertThrows(ExpectedFileOperationException::class.java) {
            validateRequestedName("   ")
        }
        assertFalse(shouldRecordOperationError(error))
    }

    @Test fun newNameCanProceed() {
        validateRequestedName("new.txt")
        rejectExistingName(false)
    }

    @Test fun actualFailureIsStillLoggable() {
        assertTrue(shouldRecordOperationError(IOException("Failure involving private filename")))
        assertTrue(shouldRecordOperationError(SecurityException("Permission lost")))
        assertTrue(shouldRecordOperationError(IllegalStateException("Rename failed")))
    }

    @Test fun normalCoroutineCancellationIsNotLoggable() {
        assertFalse(shouldRecordOperationError(CancellationException("cancelled")))
    }
}
