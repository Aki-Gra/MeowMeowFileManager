package com.meowmeow.filemanager.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.validateRequestedName
import com.meowmeow.filemanager.model.rejectExistingName

/** Scoped Storage Access Framework access: only folders selected by the user. */
class DocumentRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val preferences = context.getSharedPreferences("selected_tree", Context.MODE_PRIVATE)

    /** Restore access only if the platform still lists the grant as persisted. */
    fun restoreRoot(): DocumentFile? {
        val saved = preferences.getString("tree_uri", null) ?: return null
        val uri = Uri.parse(saved)
        val hasReadGrant = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!hasReadGrant) {
            preferences.edit().remove("tree_uri").apply()
            return null
        }
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory && it.exists() }
    }

    /** The Activity Result tree picker supplies a URI, not the original intent flags. */
    fun chooseRoot(uri: Uri): DocumentFile {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, read or write)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, read)
        }
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: error("The selected folder cannot be opened")
        check(root.isDirectory && root.exists()) { "The selected folder is not accessible" }
        preferences.edit().putString("tree_uri", uri.toString()).apply()
        return root
    }

    fun listChildren(folder: DocumentFile): List<Pair<FileEntry, DocumentFile>> =
        folder.listFiles().map { child ->
            FileEntry(
                id = child.uri.toString(),
                name = child.name ?: "Unnamed file",
                isDirectory = child.isDirectory,
                sizeBytes = child.length(),
                modifiedAtMillis = child.lastModified(),
                mimeType = child.type,
                canWrite = child.canWrite()
            ) to child
        }

    fun validateSearchEntry(file: DocumentFile, entry: FileEntry) {
        check(file.exists() && file.canRead() && file.uri.toString() == entry.id &&
            file.name == entry.name && file.isDirectory == entry.isDirectory &&
            (entry.isDirectory || file.length() == entry.sizeBytes) &&
            (entry.modifiedAtMillis <= 0 || file.lastModified() == entry.modifiedAtMillis)) {
            "Search result changed. Search again."
        }
    }

    fun createFolder(parent: DocumentFile, name: String) {
        validateRequestedName(name)
        check(parent.canWrite()) { "This folder is read-only" }
        rejectExistingName(parent.findFile(name) != null)
        check(parent.createDirectory(name) != null) { "Could not create folder" }
    }

    fun rename(file: DocumentFile, newName: String) {
        validateRequestedName(newName)
        if (file.name == newName) return
        check(file.canWrite()) { "This item is read-only" }
        val parent = file.parentFile ?: error("The parent folder is unavailable")
        rejectExistingName(parent.findFile(newName) != null)
        check(file.renameTo(newName)) { "Could not rename item" }
    }

    fun delete(file: DocumentFile) {
        check(file.canWrite()) { "This item is read-only" }
        check(file.delete()) { "Could not delete item" }
    }
}
