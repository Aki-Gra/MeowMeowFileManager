package com.meowmeow.filemanager.ui

import com.meowmeow.filemanager.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerThumbnailsTest {
    private fun item(name: String, size: Long = 1024, directory: Boolean = false,
                     mime: String? = null) = FileEntry(
        id = "content://example/document", name = name, isDirectory = directory,
        sizeBytes = size, modifiedAtMillis = 0, mimeType = mime, canWrite = true
    )

    @Test fun onlySupportedImagesReceiveThumbnails() {
        assertTrue(ExplorerThumbnails.shouldPreview(item("photo.JPG")))
        assertTrue(ExplorerThumbnails.shouldPreview(item("file", mime = "image/png")))
        assertTrue(ExplorerThumbnails.shouldPreview(item("photo.heic")))
        assertFalse(ExplorerThumbnails.shouldPreview(item("archive.zip")))
        assertFalse(ExplorerThumbnails.shouldPreview(item("vector.svg", mime = "image/svg+xml")))
        assertFalse(ExplorerThumbnails.shouldPreview(item("folder.jpg", directory = true)))
    }

    @Test fun oversizedFilesUseAnIcon() {
        assertFalse(ExplorerThumbnails.shouldPreview(item(
            "huge.jpg", ExplorerThumbnails.MAX_FILE_BYTES + 1
        )))
        assertTrue(ExplorerThumbnails.shouldPreview(item("unknown.jpg", size = 0)))
    }

    @Test fun samplingAvoidsHugeBitmapOnOlderAndroid() {
        assertEquals(1, ExplorerThumbnails.sampleSize(256, 256))
        assertEquals(1, ExplorerThumbnails.sampleSize(512, 300))
        assertEquals(2, ExplorerThumbnails.sampleSize(1000, 700))
        assertEquals(4, ExplorerThumbnails.sampleSize(2048, 1536))
        assertEquals(8, ExplorerThumbnails.sampleSize(4096, 2160))
    }
}
