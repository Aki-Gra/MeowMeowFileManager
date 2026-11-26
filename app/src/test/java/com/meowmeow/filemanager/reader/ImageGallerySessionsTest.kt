package com.meowmeow.filemanager.reader

import com.meowmeow.filemanager.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGallerySessionsTest {
    private fun item(id: String, name: String, folder: Boolean = false) = FileEntry(
        id = "content://example/$id", name = name, isDirectory = folder,
        sizeBytes = 1, modifiedAtMillis = 0, mimeType = null, canWrite = false
    )

    @Test fun preservesFolderOrderButSkipsUnrelatedFiles() {
        val first = item("1", "a.JPG")
        val pdf = item("2", "book.pdf")
        val second = item("3", "b.png")
        val folder = item("4", "folder.jpg", folder = true)
        val third = item("5", "c.webp")
        val token = ImageGallerySessions.create(second, listOf(third, pdf, folder, second, first))
        val session = ImageGallerySessions.get(token)!!
        assertEquals(listOf(third.id, second.id, first.id), session.images.map { it.id })
        assertEquals(1, session.startIndex)
        ImageGallerySessions.remove(token)
        assertNull(ImageGallerySessions.get(token))
    }

    @Test fun noNavigationWhenOnlyOneImageOrUnsupportedFile() {
        val single = item("solo", "only.jpeg")
        assertNull(ImageGallerySessions.create(single, listOf(single, item("other", "notes.txt"))))
        assertNull(ImageGallerySessions.create(item("doc", "file.pdf"), listOf(single)))
    }

    @Test fun capsVeryLargeFoldersAndKeepsSelectedPicture() {
        val entries = (0..5100).map { item(it.toString(), "photo$it.jpg") }
        val token = ImageGallerySessions.create(entries[4800], entries)
        val session = ImageGallerySessions.get(token)!!
        assertEquals(5000, session.images.size)
        assertEquals(entries[4800].id, session.images[session.startIndex].id)
        assertTrue(session.startIndex in 0 until session.images.size)
        ImageGallerySessions.remove(token)
    }
}
