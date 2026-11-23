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
        var safeReplace = true
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
        override fun supportsAtomicReplace(folder: Node) = safeReplace
        override fun atomicReplace(staged: Node, existing: Node) {
            val i = root.children.indexOf(existing)
            check(i >= 0 && root.children.contains(staged))
            root.children.remove(staged)
            root.children[i.coerceAtMost(root.children.size - 1)] =
                Node(existing.name, false, staged.bytes.clone())
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

    @Test fun suggestedNamePreservesExtensionAndFindsNextFreeName() {
        assertEquals("report (copy).pdf", suggestCopyName("report.pdf") { false })
        assertEquals("report (copy 3).pdf", suggestCopyName("report.pdf") {
            it == "report (copy).pdf" || it == "report (copy 2).pdf"
        })
        assertEquals("notes (copy)", suggestCopyName("notes") { false })
    }

    @Test fun renameOnCollisionKeepsOriginalAndNewCopy() = runBlocking {
        val source = Node("photo.jpg", false, byteArrayOf(1, 2, 3))
        val destination = Node("dst", true)
        val existing = Node("photo.jpg", false, byteArrayOf(9, 9))
        destination.children.add(existing)
        val ops = MemOps().apply { root = destination }
        val result = SafeCopy(ops).perform(
            source, destination,
            resolveConflict = { conflict ->
                assertEquals("photo (copy).jpg", conflict.suggestedName)
                CopyConflictChoice.Rename(conflict.suggestedName)
            }
        )
        assertEquals("photo (copy).jpg", result.renamedTo)
        assertEquals(2, destination.children.size)
        assertArrayEquals(byteArrayOf(9, 9), existing.bytes)
        assertArrayEquals(source.bytes, destination.children.first {
            it.name == "photo (copy).jpg"
        }.bytes)
    }

    @Test fun rePromptsWhenRenamedDestinationAlsoExists() = runBlocking {
        val source = Node("cat.jpg", false, byteArrayOf(3, 4))
        val destination = Node("dest", true)
        val original = Node("cat.jpg", false, byteArrayOf(9))
        val other = Node("cat (copy).jpg", false, byteArrayOf(8))
        destination.children.add(original)
        destination.children.add(other)
        val ops = MemOps().apply { root = destination }
        var prompts = 0
        val result = SafeCopy(ops).perform(source, destination,
            resolveConflict = {
                prompts++
                if (prompts == 1) CopyConflictChoice.Rename("cat (copy).jpg")
                else CopyConflictChoice.Rename(it.suggestedName)
            })
        assertEquals(2, prompts)
        assertEquals("cat (copy) (copy).jpg", result.renamedTo)
        assertEquals(3, destination.children.size)
        assertArrayEquals(byteArrayOf(9), original.bytes)
        assertArrayEquals(byteArrayOf(8), other.bytes)
    }

    @Test fun replaceOnCollisionIsExplicitAndVerified() = runBlocking {
        val source = Node("file.txt", false, "updated".toByteArray())
        val destination = Node("dst", true)
        val original = Node("file.txt", false, "old".toByteArray())
        destination.children.add(original)
        val ops = MemOps().apply { root = destination }
        val result = SafeCopy(ops).perform(source, destination,
            resolveConflict = { conflict ->
                assertTrue(conflict.canReplace)
                CopyConflictChoice.Replace
            }
        )
        assertTrue(result.replacedExisting)
        assertEquals(1, destination.children.size)
        assertEquals("file.txt", destination.children.single().name)
        assertArrayEquals("updated".toByteArray(), destination.children.single().bytes)
        assertArrayEquals("updated".toByteArray(), source.bytes)
    }

    @Test fun failedReplacementLeavesOriginalUntouched() {
        val source = Node("file.txt", false, byteArrayOf(1, 2, 3))
        val destination = Node("dst", true)
        val original = Node("file.txt", false, byteArrayOf(7, 8))
        destination.children.add(original)
        val ops = MemOps().apply { root = destination; failDestWrite = true }
        assertThrows(IOException::class.java) {
            runBlocking {
                SafeCopy(ops).perform(source, destination,
                    resolveConflict = { CopyConflictChoice.Replace })
            }
        }
        assertEquals(listOf(original), destination.children)
        assertArrayEquals(byteArrayOf(7, 8), original.bytes)
    }

    @Test fun replacementRejectsChangedDestinationBeforeCommit() {
        val source = Node("file.txt", false, byteArrayOf(1, 2, 3))
        val destination = Node("dst", true)
        val original = Node("file.txt", false, byteArrayOf(7, 8))
        destination.children.add(original)
        val ops = MemOps().apply { root = destination }
        assertThrows(ExpectedFileOperationException::class.java) {
            runBlocking {
                SafeCopy(ops).perform(source, destination,
                    resolveConflict = { CopyConflictChoice.Replace },
                    onProgress = { original.bytes = byteArrayOf(2, 2) })
            }
        }
        assertEquals(listOf(original), destination.children)
        assertArrayEquals(byteArrayOf(2, 2), original.bytes)
    }

    @Test fun replaceNotAvailableForFolderOrProviderWithoutAtomicSwap() = runBlocking {
        val source = Node("folder", true)
        val destination = Node("dst", true)
        destination.children.add(Node("folder", true))
        val ops = MemOps().apply { root = destination }
        try {
            SafeCopy(ops).perform(source, destination, resolveConflict = { conflict ->
                assertFalse(conflict.canReplace)
                CopyConflictChoice.Replace
            })
            throw AssertionError("Expected validation refusal")
        } catch (_: ExpectedFileOperationException) { }
        val file = Node("file.txt", false, byteArrayOf(1))
        destination.children.add(Node("file.txt", false, byteArrayOf(2)))
        ops.safeReplace = false
        try {
            SafeCopy(ops).perform(file, destination, resolveConflict = { conflict ->
                assertFalse(conflict.canReplace)
                CopyConflictChoice.Replace
            })
            throw AssertionError("Expected validation refusal")
        } catch (_: ExpectedFileOperationException) { }
        assertEquals(2, destination.children.size)
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
