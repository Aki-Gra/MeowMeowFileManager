package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFormatTest {
    @Test fun knowsPdfAndWordExtensions() {
        assertEquals(ReaderFormat.PDF, ReaderFormat.detect("invoice.PDF", null))
        assertEquals(ReaderFormat.DOC, ReaderFormat.detect("report.doc", null))
        assertEquals(ReaderFormat.DOCX, ReaderFormat.detect("report.docx", null))
        assertEquals(ReaderFormat.ODT, ReaderFormat.detect("report.odt", null))
    }

    @Test fun textLikeFilesStayInApp() {
        listOf("hello.txt", "data.json", "events.log", "readme.md", "doc.xml",
            "table.csv", "script.kt", "config.yaml", "index.html").forEach {
            assertEquals(it, ReaderFormat.TEXT, ReaderFormat.detect(it, null))
        }
    }

    @Test fun opensCommonImagesInsideApp() {
        listOf("photo.JPG", "image.jpeg", "graphic.PNG", "picture.webp",
            "motion.GIF", "drawing.bmp", "camera.heic", "image.heif",
            "photo.avif").forEach {
            assertEquals(it, ReaderFormat.IMAGE, ReaderFormat.detect(it, null))
        }
    }

    @Test fun supportsImageMimeFallbacksAndRejectsSvg() {
        assertEquals(ReaderFormat.IMAGE, ReaderFormat.detect("photo", "image/jpeg"))
        assertEquals(ReaderFormat.IMAGE, ReaderFormat.detect("unknown", "image/png; charset=binary"))
        assertEquals(ReaderFormat.IMAGE, ReaderFormat.detect("file", "image/heic"))
        assertEquals(ReaderFormat.UNSUPPORTED, ReaderFormat.detect("art.svg", "image/svg+xml"))
        assertEquals("image/jpeg", ReaderFormat.imageMimeType("photo.JPEG"))
        assertEquals(null, ReaderFormat.imageMimeType("unknown.xyz"))
    }

    @Test fun mimeFallbackAndUnknownTypes() {
        assertEquals(ReaderFormat.DOCX, ReaderFormat.detect("file", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals(ReaderFormat.TEXT, ReaderFormat.detect("file", "text/plain"))
        assertEquals(ReaderFormat.UNSUPPORTED, ReaderFormat.detect("archive.zip", "application/zip"))
        assertTrue(readerUnsupportedMessage(ReaderFormat.UNSUPPORTED).contains("reader"))
    }
}
