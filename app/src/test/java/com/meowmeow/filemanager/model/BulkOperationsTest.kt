package com.meowmeow.filemanager.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkOperationsTest {
    private class MemNode(val name: String, val folder: Boolean, var contents: ByteArray = byteArrayOf()) {
        val children = mutableListOf<MemNode>()
        var parent: MemNode? = null
        fun add(child: MemNode) { children.add(child); child.parent = this }
    }
    private class Ops: BulkOperations<MemNode> {
        override fun name(item: MemNode) = item.name
        override fun isDirectory(item: MemNode) = item.folder
        override fun mime(item: MemNode): String? = "text/plain"
        override fun size(item: MemNode) = item.contents.size.toLong()
        override fun modified(item: MemNode) = 0L
        override fun identity(item: MemNode) = System.identityHashCode(item).toString()
        override fun children(folder: MemNode) = folder.children.toList()
        override fun contains(folder: MemNode, childName: String) =
            folder.children.any { it.name == childName }
        override fun createFolder(parent: MemNode, name: String): MemNode {
            rejectExistingName(contains(parent, name))
            return MemNode(name, true).also { parent.add(it) }
        }
        override fun createFile(parent: MemNode, name: String, mime: String?): MemNode {
            rejectExistingName(contains(parent, name))
            return MemNode(name, false).also { parent.add(it) }
        }
        override fun read(file: MemNode): InputStream = ByteArrayInputStream(file.contents)
        override fun write(file: MemNode): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                file.contents = toByteArray()
            }
        }
        override fun removeCreated(item: MemNode): Boolean {
            if (item.folder && item.children.isNotEmpty()) return false
            return item.parent?.children?.remove(item) ?: false
        }
        override fun deleteSource(item: MemNode): Boolean = removeCreated(item)
    }
    private fun f(name: String, data: String) =
        MemNode(name, false, data.toByteArray())

    @Test fun bulkCopyContinuesPastConflictWithoutOverwriting() = runBlocking {
        val a = f("a.txt", "alpha")
        val b = f("b.txt", "bravo")
        val dest = MemNode("dest", true)
        val existing = f("b.txt", "original")
        dest.add(existing)
        val progress = SafeBulkOperations(Ops()).execute(
            BulkAction.COPY, listOf(BulkSelection(a,"a.txt"),BulkSelection(b,"b.txt")),dest
        )
        assertEquals(1, progress.success)
        assertEquals(1, progress.skipped)
        assertEquals(0, progress.failed)
        assertEquals(2, progress.processed)
        assertArrayEquals("original".toByteArray(), existing.contents)
        assertArrayEquals(a.contents, dest.children.first { it.name == "a.txt" }.contents)
    }

    @Test fun verifiedMoveCopiesBeforeRemovingSource() = runBlocking {
        val source = MemNode("source",true)
        val a = f("a.txt","hello")
        source.add(a)
        val dest = MemNode("dest",true)
        val progress = SafeBulkOperations(Ops()).execute(
            BulkAction.MOVE,listOf(BulkSelection(a,"a.txt")),dest
        )
        assertEquals(1,progress.success)
        assertTrue(source.children.isEmpty())
        assertArrayEquals("hello".toByteArray(),dest.children.single().contents)
    }

    @Test fun bulkDeleteIsLimitedToSelectedItems() = runBlocking {
        val source = MemNode("source",true)
        val a = f("a.txt","alpha")
        val b = f("b.txt","bravo")
        source.add(a);source.add(b)
        val progress = SafeBulkOperations(Ops()).execute(
            BulkAction.DELETE,listOf(BulkSelection(a,"a.txt"))
        )
        assertEquals(1,progress.success)
        assertEquals(listOf(b),source.children)
    }

    @Test fun nestedFolderMoveCopiesAndDeletesTree() = runBlocking {
        val source = MemNode("source",true)
        val nested = MemNode("nested",true)
        nested.add(f("inside.txt","content"))
        source.add(nested)
        val dest = MemNode("dest",true)
        val result = SafeBulkOperations(Ops()).execute(
            BulkAction.MOVE,listOf(BulkSelection(nested,"nested")),dest
        )
        assertEquals(1,result.success)
        assertTrue(source.children.isEmpty())
        assertEquals("content",dest.children.single().children.single().contents.toString(Charsets.UTF_8))
    }

    @Test fun cancellationKeepsPreviouslyCompletedItems() {
        val a=f("a.txt","a"); val b=f("b.txt","b");val dst=MemNode("dst",true)
        val ops=Ops()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                SafeBulkOperations(ops).execute(
                    BulkAction.COPY,listOf(BulkSelection(a,"a.txt"),BulkSelection(b,"b.txt")),dst
                ) { progress ->
                    if(progress.processed == 1) throw CancellationException("Stop")
                }
            }
        }
        assertEquals(1,dst.children.size)
        assertEquals("a.txt",dst.children.single().name)
    }
}
