package com.meowmeow.filemanager.reader

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Exported ACTION_VIEW entry point for files opened *from other apps*.
 *
 * This activity never hosts an editor or requests storage access. It validates
 * the caller's content URI and forwards a temporary read grant to the existing
 * private ReaderActivity. After routing, it finishes so Back returns to the
 * originating application rather than MeowMeow's Files home screen.
 */
class ExternalOpenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent?.action != Intent.ACTION_VIEW) {
                showError()
                return
            }
            val uri = intent?.data
            if (uri == null || uri.scheme != "content") {
                showError()
                return
            }

            val name = displayName(uri)
            val mime = intent.type
                ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            if (ReaderFormat.detect(name, mime) == ReaderFormat.UNSUPPORTED) {
                showError()
                return
            }

            val reader = Intent(this, ReaderActivity::class.java).apply {
                setDataAndType(uri, mime ?: "application/octet-stream")
                clipData = ClipData.newUri(contentResolver, "Document", uri)
                putExtra(ReaderActivity.EXTRA_NAME, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(reader)
        } catch (_: Exception) {
            // Never include incoming provider exception text or private file names.
            Toast.makeText(this, "Unable to open this file", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
            }
        }.getOrNull()
        // A provider's display name is advisory only; never treat it as a path.
        return (fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "Document").take(255)
    }

    private fun showError() {
        Toast.makeText(
            this, "MeowMeow cannot preview this file from this app",
            Toast.LENGTH_LONG
        ).show()
    }
}
