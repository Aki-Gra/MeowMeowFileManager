package com.meowmeow.filemanager.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineResultsTest {
    @Test
    fun cancelledScanDoesNotBecomeAUserError() {
        val exception = assertThrows(CancellationException::class.java) {
            runBlocking {
                resultUnlessCancelled<Unit> {
                    throw CancellationException("StandaloneCoroutine was cancelled")
                }
            }
        }
        assertEquals("StandaloneCoroutine was cancelled", exception.message)
    }

    @Test
    fun genuineFailureRemainsReportable() = runBlocking {
        val result = resultUnlessCancelled<Unit> {
            throw IllegalStateException("Folder is no longer accessible")
        }
        assertTrue(result.isFailure)
        assertEquals("Folder is no longer accessible", result.exceptionOrNull()?.message)
    }

    @Test
    fun successReturnsFiles() = runBlocking {
        val result = resultUnlessCancelled { listOf("photo.jpg", "notes.txt") }
        assertEquals(2, result.getOrThrow().size)
    }
}
