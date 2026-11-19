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
                    WordExtractor(ByteArrayInputStream(bytes)).use { it.text }
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
     * PdfRenderer requires a seekable descriptor. Most local and SAF documents supply one,
     * but providers that return pipes need a bounded, temporary cache copy.
     */
    fun renderPdfPage(uri: Uri, pageIndex: Int, targetWidthPx: Int): PdfPage {
        require(pageIndex >= 0)
        val resolver = context.contentResolver
        val originalResult = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                renderWithDescriptor(descriptor, pageIndex, targetWidthPx)
            } ?: throw IOException("No readable PDF file descriptor.")
        }
        originalResult.getOrNull()?.let { return it }

        // Fallback: use app-private cache only, never external storage.
        val temp = File.createTempFile("reader-", ".pdf", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { source ->
                FileOutputStream(temp).use { target ->
                    val buffer = ByteArray(16_384)
                    var total = 0L
                    while (true) {
                        val size = source.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > MAX_PDF_BYTES) throw ReaderLimitException(
                            "PDF is too large to preview safely (75 MiB limit)."
                        )
                        target.write(buffer, 0, size)
                    }
                }
            } ?: throw IOException("PDF data is unavailable.")
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                return renderWithDescriptor(fd, pageIndex, targetWidthPx)
            }
        } finally {
            temp.delete()
        }
    }

    private fun renderWithDescriptor(
        descriptor: ParcelFileDescriptor,
        pageIndex: Int,
        targetWidthPx: Int
    ): PdfPage {
        if (descriptor.statSize > MAX_PDF_BYTES) {
            throw ReaderLimitException("PDF is too large to preview safely (75 MiB limit).")
        }
        PdfRenderer(descriptor).use { renderer ->
            val count = renderer.pageCount
            require(count > 0) { "Empty PDF document" }
            require(pageIndex < count) { "Page number is out of range" }
            renderer.openPage(pageIndex).use { page ->
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
                return PdfPage(bitmap, pageIndex, count)
            }
        }
    }
}
