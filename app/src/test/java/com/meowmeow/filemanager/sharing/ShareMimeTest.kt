package com.meowmeow.filemanager.sharing

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMimeTest {
    @Test fun usesDeclaredMimeTypeForSafAndLocalFiles() {
        assertEquals("application/pdf", shareMimeType("anything.xyz", "application/pdf"))
        assertEquals("image/png", shareMimeType("file", "image/PNG; charset=binary"))
        assertEquals("audio/flac", shareMimeType("audio.flac", "audio/flac"))
    }

    @Test fun genericMimeFallsBackToExtension() {
        assertEquals("image/jpeg", shareMimeType("photo.JPG", "application/octet-stream"))
        assertEquals("image/heic", shareMimeType("photo.HEIC", null))
        assertEquals("application/pdf", shareMimeType("readme.PDF", "*/*"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            shareMimeType("letter.docx", null)
        )
        assertEquals("text/plain", shareMimeType("notes.txt", null))
        assertEquals("application/json", shareMimeType("data.json", "application/octet-stream"))
        assertEquals("application/zip", shareMimeType("backup.zip", null))
    }

    @Test fun unknownFormatsRemainShareableAsFiles() {
        assertEquals("*/*", shareMimeType("archive.unknown", null))
        assertEquals("*/*", shareMimeType("noextension", "application/octet-stream"))
    }
}
