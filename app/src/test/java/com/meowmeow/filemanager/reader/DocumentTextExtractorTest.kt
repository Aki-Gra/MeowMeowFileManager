package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class DocumentTextExtractorTest {
    @Test fun decodesBomTextFiles() {
        assertEquals("hello", DocumentTextExtractor.decodeText(byteArrayOf(
            0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 'e'.code.toByte(),
            'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        )))
    }

    @Test fun limitsInputBytes() {
        assertThrows(ReaderLimitException::class.java) {
            DocumentTextExtractor.readLimited(ByteArrayInputStream(ByteArray(100)), 32)
        }
    }
}
