package com.meowmeow.filemanager.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs on a device or emulator, not the JVM. Android's actual XML parser,
 * PdfRenderer and ContentResolver are required for these checks.
 */
@RunWith(AndroidJUnit4::class)
class ReaderAndroidIntegrationTest {
    @Test fun extractsSmallDocxAndOdtTextWithAndroidXmlParser() {
        val docx = zip("word/document.xml",
            """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>MeowMeow DOCX</w:t></w:r></w:p></w:body></w:document>""")
        assertEquals("MeowMeow DOCX", DocumentTextExtractor.extractOfficeText(
            ByteArrayInputStream(docx), ReaderFormat.DOCX
        ))
        val odt = zip("content.xml",
            """<office:document-content xmlns:office="urn:office" xmlns:text="urn:text"><office:body><office:text><text:p>MeowMeow ODT</text:p></office:text></office:body></office:document-content>""")
        assertEquals("MeowMeow ODT", DocumentTextExtractor.extractOfficeText(
            ByteArrayInputStream(odt), ReaderFormat.ODT
        ))
    }

    @Test fun rendersTwoPdfPagesFromSameRepositorySession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("reader-test-", ".pdf", context.cacheDir)
        val document = PdfDocument()
        try {
            repeat(2) { index ->
                val info = PdfDocument.PageInfo.Builder(320, 480, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawColor(if (index == 0) Color.WHITE else Color.LTGRAY)
                document.finishPage(page)
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        val repository = ReaderRepository(context)
        try {
            val uri = Uri.fromFile(file)
            val first = repository.renderPdfPage(uri, 0, 480)
            val second = repository.renderPdfPage(uri, 1, 480)
            assertEquals(2, first.totalPages)
            assertEquals(2, second.totalPages)
            assertEquals(1, second.page)
            assertTrue(second.bitmap.width in 1..MAX_RENDER_WIDTH)
            first.bitmap.recycle()
            second.bitmap.recycle()
        } finally {
            repository.close()
            file.delete()
        }
    }

    @Test fun decodesLocalImageWithoutChangingSourceFile() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("image-test-", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(150, 80, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(Color.MAGENTA)
            FileOutputStream(file).use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val length = file.length()
            val preview = ImageReaderRepository(context).load(Uri.fromFile(file))
            assertEquals(150, preview.width)
            assertEquals(80, preview.height)
            assertEquals(length, file.length())
            preview.recycle()
        } finally {
            original.recycle()
            file.delete()
        }
    }

    private fun zip(partName: String, xml: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry(partName))
            archive.write(xml.toByteArray(Charsets.UTF_8))
            archive.closeEntry()
        }
        return output.toByteArray()
    }
}
