package com.meowmeow.filemanager.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateDeletionTest {
    private class Node(val name: String, var data: ByteArray) {
        var removed = false
        var modified = 100L
        var inaccessible = false
    }
    private class Ops : BulkOperations<Node> {
        override fun name(item: Node) = item.name
        override fun isDirectory(item: Node) = false
        override fun mime(item: Node) = "text/plain"
        override fun size(item: Node) = item.data.size.toLong()
        override fun modified(item: Node) = item.modified
        override fun identity(item: Node) = "node:" + System.identityHashCode(item)
        override fun children(folder: Node) = emptyList<Node>()
        override fun contains(folder: Node, childName: String) = false
        override fun createFolder(parent: Node, name: String): Node = error("Never create")
        override fun createFile(parent: Node, name: String, mime: String?): Node =
            error("Never create")
        override fun read(file: Node): InputStream {
            if (file.inaccessible) throw SecurityException()
            return ByteArrayInputStream(file.data)
        }
        override fun write(file: Node): OutputStream = error("Never write")
        override fun removeCreated(item: Node) = error("Never remove new")
        override fun deleteSource(item: Node): Boolean {
            if (item.inaccessible) throw SecurityException()
            item.removed = true
            return true
        }
    }
    private val ops = Ops()
    private fun target(node: Node): DuplicateDeleteTarget<Node> =
        DuplicateDeleteTarget(
            node, ops.identity(node), node.name, node.data.size.toLong(),
            node.modified, MessageDigest.getInstance("SHA-256").digest(node.data).toList()
        )

    @Test fun deleteOnlyOneSelectedDuplicate() = runBlocking {
        val one = Node("one", "same".toByteArray())
        val two = Node("two", "same".toByteArray())
        val first = target(one)
        val summary = VerifiedDuplicateDeletion(ops).delete(listOf(first))
        assertEquals(1, summary.deleted)
        assertTrue(one.removed)
        assertFalse(two.removed)
    }

    @Test fun explicitlyDeleteBothCopies() = runBlocking {
        val one = Node("one", "same".toByteArray())
        val two = Node("two", "same".toByteArray())
        val summary = VerifiedDuplicateDeletion(ops).delete(listOf(target(one), target(two)))
        assertEquals(2, summary.deleted)
        assertEquals(0, summary.remaining)
        assertTrue(one.removed)
        assertTrue(two.removed)
    }

    @Test fun changedBytesEvenWhenSameSizePreventDeletion() = runBlocking {
        val one = Node("one", "same".toByteArray())
        val before = target(one)
        one.data = "edit".toByteArray()
        val result = VerifiedDuplicateDeletion(ops).delete(listOf(before))
        assertEquals(1, result.changed)
        assertFalse(one.removed)
    }

    @Test fun changedModificationTimePreventsDeletion() = runBlocking {
        val one = Node("one", "same".toByteArray())
        val before = target(one)
        one.modified++
        val result = VerifiedDuplicateDeletion(ops).delete(listOf(before))
        assertEquals(1, result.changed)
        assertFalse(one.removed)
    }

    @Test fun failedPermissionDoesNotDeleteOtherFilesSilently() = runBlocking {
        val first = Node("first", "same".toByteArray()).apply { inaccessible = true }
        val second = Node("second", "same".toByteArray())
        val result = VerifiedDuplicateDeletion(ops).delete(listOf(target(first), target(second)))
        assertEquals(1, result.failed)
        assertEquals(1, result.deleted)
        assertFalse(first.removed)
        assertTrue(second.removed)
    }

    @Test fun cancelLeavesUnprocessedCopiesIntact() {
        val one = Node("one", "same".toByteArray())
        val two = Node("two", "same".toByteArray())
        assertThrows(CancellationException::class.java) {
            runBlocking {
                VerifiedDuplicateDeletion(ops).delete(listOf(target(one), target(two))) {
                    if (it.deleted == 1) throw CancellationException("Stop")
                }
            }
        }
        assertTrue(one.removed)
        assertFalse(two.removed)
    }
}
