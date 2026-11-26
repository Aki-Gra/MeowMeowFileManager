package com.meowmeow.filemanager.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.apache.poi.hwpf.extractor.WordExtractor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/** All document processing occurs on-device. Call blocking operations from Dispatchers.IO. */
class ReaderRepository(private val context: Context) {
    data class PdfPage(val bitmap: Bitmap, val page: Int, val totalPages: Int)

    fun loadText(uri: Uri, format: ReaderFormat): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot read the selected document.")
        val text = stream.use { source ->
            when (format) {
                ReaderFormat.TEXT ->
                    DocumentTextExtractor.decodeText(DocumentTextExtractor.readLimited(source, MAX_TEXT_BYTES))
                ReaderFormat.DOCX, ReaderFormat.ODT -> {
                    // The container is bounded before parsing any XML, limiting ZIP bombs.
                    val bytes = DocumentTextExtractor.readLimited(source, MAX_WORD_BYTES)
                    DocumentTextExtractor.extractOfficeText(ByteArrayInputStream(bytes), format)
                }
                ReaderFormat.DOC -> {
                    val bytes = DocumentTextExtractor.readLimited(source, MAX_WORD_BYTES)
                    // Some Android vendors omit Java classes used by POI internals.
                    // A missing optional class must show an error, not crash the app.
                    try {
                        WordExtractor(ByteArrayInputStream(bytes)).use { it.text }
                    } catch (failure: LinkageError) {
                        throw IOException("Legacy Word document reader is unavailable.", failure)
                    }
                }
                else -> throw IllegalArgumentException("Not a text document.")
            }
        }
        if (text.isBlank()) return "This document has no readable text."
        val maxChars = 250_000
        return if (text.length > maxChars) text.take(maxChars) +
            "\n\n— Preview limited to 250,000 characters. The original file is unchanged. —"
        else text
    }

    /**
     * Reuse a single seekable PDF renderer for the lifetime of this reader.
     * PdfRenderer is not thread-safe; all render/close operations use pdfLock.
     */
    private val pdfLock = Any()
    private var pdfSession: PdfSession? = null
    private var closed = false

    private class PdfSession(
        val uri: Uri,
        val renderer: PdfRenderer,
        val descriptor: ParcelFileDescriptor,
        val privateCopy: File?
    ) {
        fun close() {
            try {
                renderer.close()
            } finally {
                runCatching { descriptor.close() }
                privateCopy?.delete()
            }
        }
    }

    /**
     * Pipe-backed providers cannot be rendered directly. Copy only those
     * documents into a bounded private file, once per document/session.
     * Malformed PDFs are not repeatedly copied and retried.
     */
    private fun openPdfSession(uri: Uri): PdfSession {
        val resolver = context.contentResolver
        var descriptor: ParcelFileDescriptor? = null
        var privateCopy: File? = null
        try {
            descriptor = resolver.openFileDescriptor(uri, "r")
            if (descriptor == null || descriptor.statSize < 0L) {
                descriptor?.close()
                descriptor = null
                privateCopy = copyPdfToPrivateCache(uri)
                descriptor = ParcelFileDescriptor.open(
                    privateCopy, ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
            if (descriptor.statSize > MAX_PDF_BYTES) {
                throw ReaderLimitException("PDF is too large to preview safely (75 MiB limit).")
            }
            // A malformed but seekable PDF fails here. Do not retry by copying.
            val renderer = PdfRenderer(descriptor)
            return PdfSession(uri, renderer, descriptor, privateCopy)
        } catch (failure: Exception) {
            runCatching { descriptor?.close() }
            privateCopy?.delete()
            throw failure
        }
    }

    private fun copyPdfToPrivateCache(uri: Uri): File {
        val file = File.createTempFile("reader-", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(file).use { target ->
                    val buffer = ByteArray(16_384)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PDF_BYTES) {
                            throw ReaderLimitException(
                                "PDF is too large to preview safely (75 MiB limit)."
                            )
                        }
                        target.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("PDF data is unavailable.")
            return file
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    /** Only one rendering at a time; page changes do not reopen/copy the PDF. */
    fun renderPdfPage(uri: Uri, pageIndex: Int, targetWidthPx: Int): PdfPage =
        synchronized(pdfLock) {
            check(!closed) { "PDF reader is closed." }
            require(pageIndex >= 0)
            val active = pdfSession
            val session = if (active?.uri == uri) active else {
                active?.close()
                pdfSession = null
                openPdfSession(uri).also { pdfSession = it }
            }
            val renderer = session.renderer
            val count = renderer.pageCount
            require(count > 0) { "Empty PDF document" }
            require(pageIndex < count) { "Page number is out of range" }
            renderer.openPage(pageIndex).use { page ->
                require(page.width > 0 && page.height > 0) { "Invalid PDF page dimensions." }
                val requested = targetWidthPx.coerceIn(300, MAX_RENDER_WIDTH)
                val scale = min(
                    requested.toDouble() / page.width.toDouble(),
                    MAX_RENDER_HEIGHT.toDouble() / page.height.toDouble()
                )
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (failure: Exception) {
                    bitmap.recycle()
                    throw failure
                }
                PdfPage(bitmap, pageIndex, count)
            }
        }

    /** Release native PDF resources and remove private temporary copies. */
    fun close() {
        synchronized(pdfLock) {
            if (closed) return
            closed = true
            pdfSession?.close()
            pdfSession = null
        }
    }
}
