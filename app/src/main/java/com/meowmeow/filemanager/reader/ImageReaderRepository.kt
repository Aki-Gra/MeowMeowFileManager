package com.meowmeow.filemanager.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Decode a bounded, read-only on-device preview without copying the source file. */
internal class ImageReaderRepository(private val context: Context) {
    companion object {
        const val MAX_IMAGE_EDGE = 2048
        const val MAX_SOURCE_BYTES = 100L * 1024L * 1024L

        /** Power-of-two sampling is required by the BitmapFactory decoder on API 26/27. */
        fun sampleSize(width: Int, height: Int, maximumEdge: Int = MAX_IMAGE_EDGE): Int {
            require(width > 0 && height > 0 && maximumEdge > 0)
            var sample = 1
            while (max(width, height).toLong() > maximumEdge.toLong() * sample &&
                sample <= Int.MAX_VALUE / 2
            ) {
                sample *= 2
            }
            return sample
        }
    }

    fun load(uri: Uri): Bitmap {
        val reported = knownSourceSize(uri)
        if (reported != null && reported > MAX_SOURCE_BYTES) {
            throw ReaderLimitException("Image is too large to preview (100 MiB limit).")
        }
        // An unbounded provider stream could be enormous even when its
        // decoded dimensions are small. Make a limited, temporary private copy
        // only when the provider cannot report a usable size.
        var temporary: File? = null
        try {
            val source = if (reported == null) {
                copyUnknownSource(uri).also { temporary = it }.let(Uri::fromFile)
            } else uri
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    decodeModern(source)
                } else {
                    decodeLegacy(source)
                }
            } catch (oom: OutOfMemoryError) {
                throw ReaderLimitException("This image is too large to display safely.")
            }
        } finally {
            temporary?.delete()
        }
    }

    private fun knownSourceSize(uri: Uri): Long? {
        val reported = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
        }.getOrNull()
        if (reported != null && reported >= 0) return reported
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0L }
    }

    private fun copyUnknownSource(uri: Uri): File {
        val temporary = File.createTempFile("image-preview-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(temporary).use { target ->
                    val buffer = ByteArray(16_384)
                    var copied = 0L
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_SOURCE_BYTES) {
                            throw ReaderLimitException("Image is too large to preview (100 MiB limit).")
                        }
                        target.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("Image is not accessible.")
            return temporary
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    private fun decodeModern(uri: Uri): Bitmap {
        val source = if (uri.scheme == "file") {
            ImageDecoder.createSource(File(requireNotNull(uri.path)))
        } else ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            if (w <= 0 || h <= 0) throw IOException("Invalid image dimensions.")
            val ratio = minOf(1.0, MAX_IMAGE_EDGE.toDouble() / max(w, h))
            decoder.setTargetSize(
                max(1, (w * ratio).roundToInt()),
                max(1, (h * ratio).roundToInt())
            )
            // Keep the bitmap displayable on all Compose-supported devices.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported or damaged image.")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("Unsupported or damaged image.")

        // Android's modern ImageDecoder handles orientation itself. Legacy
        // BitmapFactory needs the EXIF orientation applied explicitly.
        val orientation = runCatching {
            openStream(uri).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }

    private fun openStream(uri: Uri) =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Image is not accessible.")
}
