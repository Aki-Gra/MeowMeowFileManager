package com.meowmeow.filemanager.data

import com.meowmeow.filemanager.model.ExpectedFileOperationException
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class CopyDestinationTest {
    private fun place(path: String) = CopyPlace(
        CopyNode.Device(File(path)), "device", listOf(path)
    )

    @Test fun folderCannotBeCopiedInsideItself() {
        val source = place("/storage/test/Cats")
        val dest = place("/storage/test/Cats/subfolder")
        assertThrows(ExpectedFileOperationException::class.java) {
            validateCopyDestination(source, dest, isFolder = true)
        }
    }

    @Test fun siblingFolderIsAllowed() {
        val source = place("/storage/test/Cats")
        val dest = place("/storage/test/Dogs")
        validateCopyDestination(source, dest, isFolder = true)
    }

    @Test fun sameTargetIsRejected() {
        val source = place("/storage/test/Cats")
        assertThrows(ExpectedFileOperationException::class.java) {
            validateCopyDestination(source, source, isFolder = true)
        }
    }
}
