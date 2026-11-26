package com.meowmeow.filemanager.sharing

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.meowmeow.filemanager.model.FileEntry
import java.io.File

/**
 * Share the original file, never a rendered PDF page, image bitmap or text preview.
 * Nothing is transmitted until the user chooses a destination in Android's sharesheet.
 */
object FileSharing {
    fun uriForEntry(activity: Activity, entry: FileEntry): Uri {
        require(!entry.isDirectory) { "Folders cannot be shared as a single file." }
        val original = Uri.parse(entry.id)
        return when (original.scheme) {
            "content" -> original
            "file" -> FileProvider.getUriForFile(
                activity, activity.packageName + ".fileprovider",
                File(requireNotNull(original.path))
            )
            else -> throw IllegalArgumentException("Unsupported file location.")
        }
    }

    fun shareEntry(activity: Activity, entry: FileEntry) {
        shareUri(activity, uriForEntry(activity, entry), entry.name, entry.mimeType)
    }

    fun shareUri(activity: Activity, uri: Uri, name: String, mimeType: String?) {
        require(uri.scheme == "content") { "Sharing requires a grantable content URI." }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = shareMimeType(name, mimeType)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(activity.contentResolver, "Shared file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share file").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(chooser)
    }
}
