package com.meowmeow.filemanager.reader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Read text for preview only; never execute scripts or load remote resources. */
internal object DocumentTextExtractor {
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_CHARACTERS = 2_000_000
    // Maximum total inflation from all ZIP parts, including discarded entries.
    private const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    private const val MAX_SKIPPED_PART_BYTES = 8 * 1024 * 1024

    fun extractOfficeText(source: InputStream, format: ReaderFormat): String {
        require(format == ReaderFormat.DOCX || format == ReaderFormat.ODT)
        val requested = if (format == ReaderFormat.DOCX) "word/document.xml" else "content.xml"
        val xml = ZipInputStream(source).use { archive ->
            val buffer = ByteArray(8192)
            var entryCount = 0
            var expandedTotal = 0L
            var extracted: ByteArray? = null
            while (true) {
                val entry = archive.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) {
                    throw ReaderLimitException("Document has too many parts to preview.")
                }
                val isDocument = !entry.isDirectory && entry.name == requested
                val output = if (isDocument) ByteArrayOutputStream() else null
                var partBytes = 0L
                // Explicitly drain skipped entries. ZipInputStream.closeEntry()
                // inflates the rest without giving us a chance to enforce a limit.
                while (true) {
                    val read = archive.read(buffer)
                    if (read < 0) break
                    partBytes += read.toLong()
                    expandedTotal += read.toLong()
                    if (expandedTotal > MAX_EXPANDED_BYTES ||
                        partBytes > if (isDocument) MAX_XML_BYTES.toLong()
                                    else MAX_SKIPPED_PART_BYTES.toLong()
                    ) {
                        throw ReaderLimitException("Document archive is too large to preview safely.")
                    }
                    output?.write(buffer, 0, read)
                }
                archive.closeEntry()
                if (isDocument) {
                    extracted = output!!.toByteArray()
                    break
                }
            }
            extracted ?: throw IOException("Document text part was not found.")
        }
        val xmlPreview = String(xml, Charsets.UTF_8)
        if (xmlPreview.contains("<!DOCTYPE", ignoreCase = true) ||
            xmlPreview.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IOException("XML entity declarations are not supported.")
        }
        return extractXmlText(ByteArrayInputStream(xml), format)
    }

    private fun extractXmlText(stream: InputStream, format: ReaderFormat): String {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        // No DTD or external entity resolution. Never load URLs referenced by a document.
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, "UTF-8")
        val output = StringBuilder()
        var collecting = false
        var spaceDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name ?: ""
            if (format == ReaderFormat.DOCX) {
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "t" -> collecting = true
                        "tab" -> output.append('\t')
                        "br", "cr" -> output.append('\n')
                    }
                    XmlPullParser.TEXT -> if (collecting) output.append(parser.text)
                    XmlPullParser.END_TAG -> when (tag) {
                        "t" -> collecting = false
                        "p" -> output.append('\n')
                        "tc" -> output.append('\t')
                    }
                }
            } else {
                when (event) {
                    XmlPullParser.START_TAG -> when (tag) {
                        "p", "h" -> { collecting = true; spaceDepth++ }
                        "tab" -> if (collecting) output.append('\t')
                        "line-break" -> if (collecting) output.append('\n')
                    }
                    XmlPullParser.TEXT -> if (collecting) output.append(parser.text)
                    XmlPullParser.END_TAG -> when (tag) {
                        "p", "h" -> {
                            if (collecting) output.append('\n')
                            spaceDepth--
                            collecting = spaceDepth > 0
                        }
                    }
                }
            }
            if (output.length > MAX_CHARACTERS) {
                throw ReaderLimitException("Document text is too large for a preview.")
            }
            event = parser.next()
        }
        return output.toString().trim().ifEmpty { "This document has no readable text." }
    }

    fun readLimited(input: InputStream, byteLimit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var read: Int
        var total = 0
        while (input.read(buffer).also { read = it } != -1) {
            total += read
            if (total > byteLimit) throw ReaderLimitException("Document is too large to preview safely.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8)
    }
}
