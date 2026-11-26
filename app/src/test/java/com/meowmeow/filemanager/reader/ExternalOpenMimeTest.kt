package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalOpenMimeTest {
    @Test fun recognizedMimeWithoutExtensionOpensCorrectReader() {
        val cases = mapOf(
            "application/pdf" to ReaderFormat.PDF,
            "application/msword" to ReaderFormat.DOC,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to ReaderFormat.DOCX,
            "application/vnd.oasis.opendocument.text" to ReaderFormat.ODT,
            "image/jpeg" to ReaderFormat.IMAGE,
            "image/png" to ReaderFormat.IMAGE,
            "image/gif" to ReaderFormat.IMAGE,
            "image/webp" to ReaderFormat.IMAGE,
            "image/heic" to ReaderFormat.IMAGE,
            "image/avif" to ReaderFormat.IMAGE,
            "text/plain" to ReaderFormat.TEXT,
            "text/markdown" to ReaderFormat.TEXT,
            "application/json" to ReaderFormat.TEXT,
            "application/ld+json" to ReaderFormat.TEXT,
            "application/x-ndjson" to ReaderFormat.TEXT,
            "application/xml" to ReaderFormat.TEXT,
            "application/xhtml+xml" to ReaderFormat.TEXT,
            "application/x-yaml" to ReaderFormat.TEXT,
            "application/yaml" to ReaderFormat.TEXT,
            "application/toml" to ReaderFormat.TEXT,
            "application/x-toml" to ReaderFormat.TEXT,
            "application/javascript" to ReaderFormat.TEXT,
            "application/ecmascript" to ReaderFormat.TEXT,
            "application/sql" to ReaderFormat.TEXT
        )
        cases.forEach { (mime, expected) ->
            assertEquals(mime, expected, ReaderFormat.detect("unnamed", mime))
        }
    }

    @Test fun extensionRecognitionHandlesGenericProviderMimesInsideReader() {
        listOf("file.PDF", "file.DOCX", "file.ODT", "photo.JPG", "image.AVIF",
            "notes.MD", "data.JSON", "script.KT", "config.YAML").forEach { filename ->
            assertTrue(filename, ReaderFormat.detect(filename, "application/octet-stream")
                != ReaderFormat.UNSUPPORTED)
        }
        assertEquals(
            ReaderFormat.UNSUPPORTED,
            ReaderFormat.detect("installer.apk", "application/vnd.android.package-archive")
        )
    }

    @Test fun manifestAdvertisesSupportedMimeTypesButNotAllBinaryOrSvg() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".reader.ExternalOpenActivity\""))
        assertTrue(manifest.contains("android:name=\"android.intent.action.VIEW\""))
        assertTrue(manifest.contains("android:name=\"android.intent.category.DEFAULT\""))
        assertTrue(manifest.contains("android:scheme=\"content\""))
        val mimes = listOf("application/pdf", "text/*", "application/json",
            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text", "image/jpeg", "image/png",
            "image/webp", "image/gif", "image/heic", "image/avif")
        mimes.forEach { mime ->
            assertTrue("No VIEW handler for $mime", manifest.contains("android:mimeType=\"$mime\""))
        }
        assertTrue("Do not advertise generic files in the chooser",
            !manifest.contains("android:mimeType=\"application/octet-stream\""))
        assertTrue("SVG rendering is not built-in",
            !manifest.contains("android:mimeType=\"image/svg+xml\""))
    }
}
