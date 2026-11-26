package com.meowmeow.filemanager.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import android.util.LruCache
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.reader.ReaderFormat
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Small in-memory thumbnails, only decoded for on-screen grid cells.
 * No disk cache, background indexing, remote requests, or filename logging.
 */
internal class ExplorerThumbnails(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }

    companion object {
        const val EDGE = 256
        const val MAX_FILE_BYTES = 48L * 1024L * 1024L

        fun shouldPreview(entry: FileEntry): Boolean =
            !entry.isDirectory &&
                (entry.sizeBytes <= 0L || entry.sizeBytes <= MAX_FILE_BYTES) &&
                ReaderFormat.detect(entry.name, entry.mimeType) == ReaderFormat.IMAGE

        /** inSampleSize is power-of-two and never allows a huge legacy bitmap. */
        fun sampleSize(width: Int, height: Int): Int {
            if (width <= 0 || height <= 0) return 1
            var sample = 1
            while (max(width, height).toLong() > (EDGE * 2).toLong() * sample &&
                sample < 1 shl 20) {
                sample *= 2
            }
            return sample
        }
    }

    fun load(entry: FileEntry): Bitmap? {
        if (!shouldPreview(entry)) return null
        val key = entry.id + "|" + entry.sizeBytes + "|" + entry.modifiedAtMillis
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val image = runCatching {
            val original = Uri.parse(entry.id)
            val uri = if (original.scheme == "file") {
                FileProvider.getUriForFile(
                    context, context.packageName + ".fileprovider",
                    File(requireNotNull(original.path))
                )
            } else if (original.scheme == "content") original else return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0)
                    val scale = minOf(1.0, EDGE.toDouble() / max(width, height))
                    decoder.setTargetSize(
                        max(1, (width * scale).roundToInt()),
                        max(1, (height * scale).roundToInt())
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                decodeLegacy(uri)
            }
        }.getOrNull() ?: return null

        if (image.byteCount > 2 * 1024 * 1024) {
            image.recycle()
            return null
        }
        cache.put(key, image)
        return image
    }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } catch (_: Exception) {
            bitmap
        }
    }
}
