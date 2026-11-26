package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextExtractorTest {
    @Test fun decodesBomTextFiles() {
        assertEquals("hello", DocumentTextExtractor.decodeText(byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        )))
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun drainsSmallArchiveAndRejectsMissingTextPart() {
        // Runs on the JVM without invoking Android's platform XmlPullParser.
        val docx = archive(
            "metadata.xml" to "<meta/>".toByteArray(),
            "word/styles.xml" to "<styles/>".toByteArray()
        )
        assertThrows(IOException::class.java) {
            DocumentTextExtractor.extractOfficeText(ByteArrayInputStream(docx), ReaderFormat.DOCX)
        }
    }

    @Test fun blocksLargeDecompressionInSkippedPart() {
        // Highly compressible payload: small archive, big decompressed entry.
        val bomb = archive(
            "skipped.bin" to ByteArray(9 * 1024 * 1024),
            "word/document.xml" to "<w:t>Too late</w:t>".toByteArray()
        )
        assertThrows(ReaderLimitException::class.java) {
            DocumentTextExtractor.extractOfficeText(ByteArrayInputStream(bomb), ReaderFormat.DOCX)
        }
    }

    @Test fun limitsInputBytes() {
        assertThrows(ReaderLimitException::class.java) {
            DocumentTextExtractor.readLimited(ByteArrayInputStream(ByteArray(100)), 32)
        }
    }
}
