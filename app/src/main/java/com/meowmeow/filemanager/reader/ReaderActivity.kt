package com.meowmeow.filemanager.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.sharing.FileSharing
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.ui.theme.MeowMeowTheme

/**
 * Not exported: opened by MeowMeow's file list rather than Android's system chooser.
 * A separate activity keeps the reader isolated from Files/Storage/Cleanup UI state.
 */
class ReaderActivity : ComponentActivity() {
    private val logger get() = (application as MeowMeowApp).errorLog
    private var activeRepository: ReaderRepository? = null

    override fun onDestroy() {
        if (isFinishing) {
            ImageGallerySessions.remove(intent?.getStringExtra(EXTRA_GALLERY_TOKEN))
        }
        val repository = activeRepository
        activeRepository = null
        // Closing a PDF renderer can wait for the current render to finish.
        // Keep that work off the UI thread during Activity teardown.
        if (repository != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.close() }
            }
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val documentUri = intent.data
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Document" }
        val mime = intent.type
        if (documentUri == null || documentUri.scheme != "content") {
            Toast.makeText(this, "This file could not be opened", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val galleryToken = intent.getStringExtra(EXTRA_GALLERY_TOKEN)
        val gallery = ImageGallerySessions.get(galleryToken)
        val repository = ReaderRepository(applicationContext)
        activeRepository = repository
        setContent {
            var currentIndex by rememberSaveable(galleryToken) {
                mutableIntStateOf(gallery?.startIndex ?: 0)
            }
            var currentUriString by rememberSaveable {
                mutableStateOf(documentUri.toString())
            }
            var currentName by rememberSaveable { mutableStateOf(name) }
            var currentMime by rememberSaveable { mutableStateOf(mime) }
            val currentUri = Uri.parse(currentUriString)
            val currentFormat = ReaderFormat.detect(currentName, currentMime)
            val externalMime = if (currentFormat == ReaderFormat.IMAGE &&
                (currentMime.isNullOrBlank() || currentMime == "application/octet-stream" ||
                    currentMime == "*/*")
            ) ReaderFormat.imageMimeType(currentName) ?: currentMime else currentMime

            fun moveImage(offset: Int) {
                val next = gallery?.images?.getOrNull(currentIndex + offset) ?: return
                try {
                    val nextUri = FileSharing.uriForEntry(this, next)
                    currentUriString = nextUri.toString()
                    currentName = next.name
                    currentMime = next.mimeType
                    currentIndex += offset
                } catch (failure: Exception) {
                    logger.record(
                        ErrorArea.DOCUMENT_READER, failure, operation = ErrorOperation.READER_OPEN
                    )
                    Toast.makeText(this, "Unable to open the next picture", Toast.LENGTH_SHORT).show()
                }
            }

            MeowMeowTheme {
                ReaderScreen(
                    title = currentName,
                    format = currentFormat,
                    uri = currentUri,
                    repository = repository,
                    onClose = ::finish,
                    onShare = { shareFile(currentUri, currentName, externalMime) },
                    onExternal = { openExternally(currentUri, externalMime) },
                    galleryUnavailable = galleryToken != null && gallery == null,
                    galleryPosition = if (gallery != null) currentIndex + 1 else null,
                    galleryCount = gallery?.images?.size,
                    onPreviousImage = if (gallery != null && currentIndex > 0)
                        ({ moveImage(-1) }) else null,
                    onNextImage = if (gallery != null &&
                        currentIndex + 1 < gallery.images.size) ({ moveImage(1) }) else null,
                    onReaderFailure = { error, operation ->
                        if (error !is ReaderLimitException) {
                            logger.record(ErrorArea.DOCUMENT_READER, error, operation = operation)
                        }
                    }
                )
            }
        }
    }

    private fun shareFile(uri: Uri, name: String, mime: String?) {
        try {
            FileSharing.shareUri(this, uri, name, mime)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No sharing app is available", Toast.LENGTH_LONG).show()
        } catch (failure: Exception) {
            logger.record(ErrorArea.FILE_SHARING, failure, operation = ErrorOperation.FILE_SHARE)
            Toast.makeText(this, "Unable to share this file", Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternally(uri: Uri, mime: String?) {
        try {
            val external = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "application/octet-stream")
                clipData = ClipData.newUri(contentResolver, "MeowMeow document", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Exclude our own exported handler to avoid reopening MeowMeow in a loop.
            val chooser = Intent.createChooser(external, "Open with another app").apply {
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(this@ReaderActivity, ExternalOpenActivity::class.java))
                )
            }
            startActivity(chooser)
        } catch (failure: ActivityNotFoundException) {
            Toast.makeText(this, "No other app can open this document", Toast.LENGTH_LONG).show()
        } catch (failure: Exception) {
            logger.record(ErrorArea.EXTERNAL_FILE_OPEN, failure, operation = ErrorOperation.EXTERNAL_OPEN)
            Toast.makeText(this, "Unable to open with another app", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_NAME = "reader_display_name"
        const val EXTRA_GALLERY_TOKEN = "reader_image_gallery_token"
    }
}
