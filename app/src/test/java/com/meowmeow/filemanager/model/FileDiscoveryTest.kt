package com.meowmeow.filemanager.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FileDiscoveryTest {
    private fun entry(name: String, mime: String? = null, directory: Boolean = false) =
        FileEntry(name, name, directory, 120L, 0L, mime, true)

    @Test fun typesUseMIMEAndKnownExtensionsWithoutReadingContents() {
        assertEquals(FileKind.IMAGES, detectFileKind(entry("cat.JPG", "application/octet-stream")))
        assertEquals(FileKind.DOCUMENTS, detectFileKind(entry("spreadsheet.xlsx", null)))
        assertEquals(FileKind.DOCUMENTS, detectFileKind(entry("report", "application/pdf")))
        assertEquals(FileKind.ARCHIVES, detectFileKind(entry("files.zip", "application/zip")))
        assertEquals(FileKind.ARCHIVES, detectFileKind(entry("files.rar", "application/vnd.rar")))
        assertEquals(FileKind.APKS, detectFileKind(entry("app.apk", null)))
        assertEquals(FileKind.OTHER, detectFileKind(entry("unknown")))
        assertEquals(FileKind.OTHER, detectFileKind(entry("folder", directory = true)))
        assertFalse(matchesFileSearch(entry("photo.jpg", directory = true), "", FileKind.IMAGES))
        assertTrue(matchesFileSearch(entry("Holiday.JPG"), "HOLIday", FileKind.ALL))
    }

    @Test fun currentFolderIsNotRecursive() = runBlocking {
        val tree = mapOf(
            "root" to listOf(entry("nested", directory = true), entry("cat.jpg")),
            "nested" to listOf(entry("otherCat.jpg"))
        )
        val scanner = FileDiscoveryScanner<String>({ it }, { folder ->
            tree[folder].orEmpty().map { it to it.name }
        })
        val outcome = scanner.scan("root", false, "cat", FileKind.ALL)
        assertEquals(listOf("cat.jpg"), outcome.matches.map { it.entry.name })
        assertEquals(1, outcome.foldersVisited)
        assertFalse(outcome.truncated)
    }

    @Test fun recursiveSearchIncludesLocationAndRespectsFilters() = runBlocking {
        val tree = mapOf(
            "root" to listOf(entry("photos", directory = true), entry("cat.docx")),
            "photos" to listOf(entry("Cat.PNG", "image/png"), entry("notes.txt"))
        )
        val scanner = FileDiscoveryScanner<String>({ it }, { folder ->
            tree[folder].orEmpty().map { it to it.name }
        })
        val outcome = scanner.scan("root", true, "cat", FileKind.IMAGES)
        assertEquals(1, outcome.matches.size)
        assertEquals("Cat.PNG", outcome.matches.single().entry.name)
        assertEquals("photos", outcome.matches.single().location)
        assertEquals(listOf("root", "photos"), outcome.matches.single().folderTrail)
    }

    @Test fun truncationIsExplicitAndUnreadableFoldersAreCounted() = runBlocking {
        val scanner = FileDiscoveryScanner<String>({ it }, { folder ->
            if (folder == "bad") throw SecurityException("Cannot access")
            when (folder) {
                "root" -> listOf(entry("bad", directory = true), entry("lots", directory = true))
                    .map { it to it.name }
                "lots" -> (1..10).map { entry("document$it.txt") to "file$it" }
                else -> emptyList()
            }
        })
        val out = scanner.scan("root", true, "", FileKind.ALL, maxResults = 5)
        assertEquals(5, out.matches.size)
        assertTrue(out.truncated)
        assertEquals(1, out.unreadableFolders)
    }

    @Test fun cancellationNeverBecomesSuccess() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                FileDiscoveryScanner<String>({ it }, { throw CancellationException("Cancelled") })
                    .scan("root", true, "", FileKind.ALL)
            }
        }
    }
}
