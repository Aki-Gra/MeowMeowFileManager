package com.meowmeow.filemanager.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

class SafeCopyTest {
    class Node(val name: String, val folder: Boolean, var bytes: ByteArray = byteArrayOf()) {
        val children = mutableListOf<Node>()
        var corruptRead = false
    }

    private class MemOps : CopyOperations<Node> {
        var corruptDestRead = false
        var failDestWrite = false
        private val createdFiles = mutableSetOf<Node>()
        override fun name(item: Node) = item.name
        override fun isDirectory(item: Node) = item.folder
        override fun mime(item: Node): String? = "application/octet-stream"
        override fun size(item: Node) = if (item.folder) 0L else item.bytes.size.toLong()
        override fun modified(item: Node) = 0L
        override fun identity(item: Node) = System.identityHashCode(item).toString()
        override fun children(folder: Node) = folder.children.toList()
        override fun contains(folder: Node, childName: String) = folder.children.any { it.name == childName }
        override fun createFolder(parent: Node, name: String) =
            Node(name, true).also { parent.children.add(it) }
        override fun createFile(parent: Node, name: String, mime: String?) =
            Node(name, false).also { parent.children.add(it); createdFiles.add(it) }
        override fun read(file: Node): InputStream =
            ByteArrayInputStream(if (file.corruptRead || (corruptDestRead && file in createdFiles))
                byteArrayOf(0x00) else file.bytes)
        override fun write(file: Node): OutputStream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failDestWrite && file in createdFiles)
                    throw IOException("Simulated write interruption")
                super.write(b, off, len)
            }
            override fun close() {
                super.close()
                file.bytes = toByteArray()
            }
        }
        override fun removeCreated(item: Node): Boolean {
            fun remove(parent: Node): Boolean {
                if (parent.children.remove(item)) return true
                return parent.children.any { remove(it) }
            }
            return remove(root)
        }
        lateinit var root: Node
    }

    @Test fun copiesFileAndPreservesSource() = runBlocking {
        val source = Node("cat.txt", false, "hello cat".toByteArray())
        val target = Node("destination", true)
        val ops = MemOps().apply { root = target }
        val totals = SafeCopy(ops).perform(source, target)
        assertEquals(1, totals.copiedFiles)
        assertEquals(9L, totals.bytes)
        assertArrayEquals(source.bytes, target.children.single().bytes)
        assertEquals("hello cat", source.bytes.toString(Charsets.UTF_8))
    }

    @Test fun copiesNestedDirectories() = runBlocking {
        val root = Node("root", true)
        val source = Node("pics", true).also { root.children.add(it) }
        val sub = Node("cats", true).also { source.children.add(it) }
        sub.children.add(Node("photo.png", false, byteArrayOf(1, 2, 3)))
        val destination = Node("other", true).also { root.children.add(it) }
        val stats = SafeCopy(MemOps().apply { this.root = root }).perform(source, destination)
        assertEquals(1, stats.copiedFiles)
        assertEquals(2, stats.copiedFolders)
        assertEquals(3L, stats.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3),
            destination.children.single().children.single().children.single().bytes)
    }

    @Test fun duplicateDoesNotOverwriteOrDeleteExistingFile() {
        val target = Node("dst", true)
        val existing = Node("file.txt", false, byteArrayOf(9))
        target.children.add(existing)
        val ops = MemOps().apply { root = target }
        assertThrows(ExpectedFileOperationException::class.java) {
            runBlocking { SafeCopy(ops).perform(Node("file.txt", false, byteArrayOf(7)), target) }
        }
        assertEquals(1, target.children.size)
        assertArrayEquals(byteArrayOf(9), existing.bytes)
    }

    @Test fun cancellationRemovesOnlyCreatedTargetAndPreservesSource() {
        val source = Node("src", true)
        source.children.add(Node("file.txt", false, byteArrayOf(4, 5)))
        val target = Node("dst", true)
        val ops = MemOps().apply { root = target }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                SafeCopy(ops).perform(source, target) {
                    throw CancellationException("User cancelled")
                }
            }
        }
        assertTrue(target.children.isEmpty())
        assertEquals(1, source.children.size)
    }

    @Test fun corruptedDestinationIsRejectedAndRemoved() {
        val source = Node("important.pdf", false, byteArrayOf(1, 2, 3))
        val destination = Node("dst", true)
        val ops = MemOps().apply { root = destination; corruptDestRead = true }
        assertThrows(IOException::class.java) {
            runBlocking { SafeCopy(ops).perform(source, destination) }
        }
        assertTrue(destination.children.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), source.bytes)
    }

    @Test fun failedWriteDoesNotLeavePartialFile() {
        val source = Node("important.pdf", false, byteArrayOf(3, 2, 1))
        val destination = Node("dst", true)
        val ops = MemOps().apply { root = destination; failDestWrite = true }
        assertThrows(IOException::class.java) {
            runBlocking { SafeCopy(ops).perform(source, destination) }
        }
        assertTrue(destination.children.isEmpty())
        assertArrayEquals(byteArrayOf(3, 2, 1), source.bytes)
    }

    @Test fun recursiveCycleRollsBack() {
        val source = Node("folder", true)
        source.children.add(source)
        val target = Node("dest", true)
        val ops = MemOps().apply { root = target }
        assertThrows(IOException::class.java) {
            runBlocking { SafeCopy(ops).perform(source, target) }
        }
        assertTrue(target.children.isEmpty())
    }
}
