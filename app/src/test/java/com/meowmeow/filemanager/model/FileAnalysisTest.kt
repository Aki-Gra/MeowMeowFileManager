package com.meowmeow.filemanager.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAnalysisTest {
    private class Node(
        val id: String, val folder: Boolean = false, var data: ByteArray = byteArrayOf()
    ) {
        val children = arrayListOf<Node>()
        var inaccessible = false
    }
    private val ops = object : CopyOperations<Node> {
        override fun name(item: Node) = item.id
        override fun isDirectory(item: Node) = item.folder
        override fun mime(item: Node) = "application/octet-stream"
        override fun size(item: Node) = item.data.size.toLong()
        override fun modified(item: Node) = 0L
        override fun identity(item: Node) = item.id
        override fun children(folder: Node): List<Node> {
            if (folder.inaccessible) throw SecurityException()
            return folder.children.toList()
        }
        override fun contains(folder: Node, childName: String) = false
        override fun createFolder(parent: Node, name: String): Node = error("Read-only test")
        override fun createFile(parent: Node, name: String, mime: String?): Node = error("Read-only test")
        override fun read(file: Node): InputStream {
            if (file.inaccessible) throw SecurityException()
            return ByteArrayInputStream(file.data)
        }
        override fun write(file: Node): OutputStream = error("Read-only test")
        override fun removeCreated(item: Node): Boolean = error("Read-only test")
    }

    private fun text(name: String, body: String) =
        Node(name, data = body.toByteArray())

    @Test fun exactDuplicatesHaveIdenticalBytesNotJustNames() = runBlocking {
        val root = Node("root", folder = true)
        root.children.addAll(listOf(
            text("a", "hello"), text("b", "hello"),
            text("same-name", "something else"), text("unique", "different")
        ))
        val result = FileAnalysisScanner(ops).scan(root, AnalysisKind.DUPLICATES, false)
        assertEquals(1, result.duplicateGroups.size)
        assertEquals(2, result.duplicateGroups.single().members.size)
        assertEquals(5L, result.reclaimableBytes)
        assertEquals(setOf("a", "b"),
            result.duplicateGroups.single().members.map { it.name }.toSet())
    }

    @Test fun identicalFileNamesWithDifferentContentsDoNotMatch() = runBlocking {
        val root = Node("root", folder = true)
        root.children.addAll(listOf(
            text("shared", "abc"), text("shared", "def")
        ))
        val result = FileAnalysisScanner(ops).scan(root, AnalysisKind.DUPLICATES, false)
        assertTrue(result.duplicateGroups.isEmpty())
    }

    @Test fun currentFolderScopeAndRecursiveScopeDiffer() = runBlocking {
        val root = Node("root", folder = true)
        val sub = Node("sub", folder = true)
        root.children.add(text("top", "content"))
        root.children.add(sub)
        sub.children.add(text("nested", "content"))
        val shallow = FileAnalysisScanner(ops).scan(root, AnalysisKind.DUPLICATES, false)
        val deep = FileAnalysisScanner(ops).scan(root, AnalysisKind.DUPLICATES, true)
        assertTrue(shallow.duplicateGroups.isEmpty())
        assertEquals(1, deep.duplicateGroups.size)
    }

    @Test fun largeFilesAreSortedAndRespectThreshold() = runBlocking {
        val root = Node("root", folder = true)
        root.children.add(text("small", "a"))
        root.children.add(text("middle", "abc"))
        root.children.add(text("big", "abcdefgh"))
        val result = FileAnalysisScanner(ops).scan(
            root, AnalysisKind.LARGE, recursive = false, minimumBytes = 3
        )
        assertEquals(listOf("big", "middle"), result.largeFiles.map { it.name })
        assertFalse(result.truncated)
    }

    @Test fun unreadableFolderAndScanLimitsShowIncompleteResults() = runBlocking {
        val root = Node("root", folder = true)
        val blocked = Node("blocked", folder = true).apply { inaccessible = true }
        root.children.add(blocked)
        root.children.add(text("one", "aa"))
        val missing = FileAnalysisScanner(ops).scan(root, AnalysisKind.LARGE, true, minimumBytes = 1)
        assertEquals(1, missing.unreadable)
        val limit = FileAnalysisScanner(ops).scan(
            root, AnalysisKind.LARGE, false, minimumBytes = 1, maxEntries = 1
        )
        assertTrue(limit.truncated)
    }

    @Test fun hashBudgetIsExplicitlyMarkedIncomplete() = runBlocking {
        val root = Node("root", folder = true)
        root.children.add(text("one", "hello"))
        root.children.add(text("two", "hello"))
        val outcome = FileAnalysisScanner(ops).scan(
            root, AnalysisKind.DUPLICATES, false, maxHashBytes = 5
        )
        assertTrue(outcome.truncated)
        assertTrue(outcome.duplicateGroups.isEmpty())
    }

    @Test fun cancelStopsScanRatherThanReportingSuccess() {
        val root = Node("root", folder = true)
        root.children.add(text("a", "abc"))
        assertThrows(CancellationException::class.java) {
            runBlocking {
                FileAnalysisScanner(ops).scan(root, AnalysisKind.LARGE, false, 1) {
                    _, _, _ -> throw CancellationException("cancel scan")
                }
            }
        }
    }
}
