package com.meowmeow.filemanager.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        val format = ReaderFormat.detect(name, mime)
        val externalMime = if (format == ReaderFormat.IMAGE &&
            (mime.isNullOrBlank() || mime == "application/octet-stream" || mime == "*/*")
        ) ReaderFormat.imageMimeType(name) ?: mime else mime
        val repository = ReaderRepository(applicationContext)
        setContent {
            MeowMeowTheme {
                ReaderScreen(
                    title = name,
                    format = format,
                    uri = documentUri,
                    repository = repository,
                    onClose = ::finish,
                    onShare = { shareFile(documentUri, name, externalMime) },
                    onExternal = { openExternally(documentUri, externalMime) },
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
    }
}
