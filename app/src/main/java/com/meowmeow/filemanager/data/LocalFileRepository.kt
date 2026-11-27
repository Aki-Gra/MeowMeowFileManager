package com.meowmeow.filemanager.data

import android.net.Uri
import android.os.Build
import android.os.Environment
import com.meowmeow.filemanager.model.CleanupCandidate
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.isLargeDownload
import com.meowmeow.filemanager.model.validateRequestedName
import com.meowmeow.filemanager.model.rejectExistingName
import java.io.File
import java.util.ArrayDeque

/**
 * Access to shared internal storage after the user enables Android's special All files access.
 * Never attempt to walk outside this storage volume or follow symlinks.
 * Android's own restrictions on Android/data and Android/obb remain in force.
 */
class LocalFileRepository {
    private val root = Environment.getExternalStorageDirectory().canonicalFile

    fun hasAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun root(): File = checked(root)

    fun downloads(): File = checked(File(root, Environment.DIRECTORY_DOWNLOADS))

    private fun checked(file: File): File {
        check(hasAccess()) { "Enable All files access in Android settings to browse device storage." }
        val canonical = file.canonicalFile
        val rootPath = root.path + File.separator
        require(canonical == root || canonical.path.startsWith(rootPath)) {
            "Outside shared device storage."
        }
        val relative = canonical.relativeTo(root).invariantSeparatorsPath
        require(
            relative != "Android/data" &&
                !relative.startsWith("Android/data/") &&
                relative != "Android/obb" &&
                !relative.startsWith("Android/obb/")
        ) { "Android restricts access to this system-managed folder." }
        return canonical
    }

    /** Used by copy to revalidate every source/destination before I/O. */
    fun checkedForCopy(file: File): File {
        val approved = checked(file)
        check(file.absoluteFile == approved &&
            !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            "Copy cannot follow symbolic links or protected paths."
        }
        return approved
    }

    fun fromEntry(entry: FileEntry): File {
        val uri = Uri.parse(entry.id)
        require(uri.scheme == "file") { "Not a local device file." }
        return checked(File(requireNotNull(uri.path)))
    }

    fun validateSearchEntry(file: File, entry: FileEntry) {
        val target = checkedForCopy(file)
        check(target.exists() && target.canRead() && Uri.fromFile(target).toString() == entry.id &&
            target.name == entry.name && target.isDirectory == entry.isDirectory &&
            (entry.isDirectory || target.length() == entry.sizeBytes) &&
            (entry.modifiedAtMillis <= 0 || target.lastModified() == entry.modifiedAtMillis)) {
            "Search result changed. Search again."
        }
    }

    fun listChildren(folder: File): List<FileEntry> {
        val parent = checked(folder)
        check(parent.isDirectory && parent.canRead()) { "Folder is not readable." }
        return (parent.listFiles() ?: error("Folder contents are unavailable.")).mapNotNull { child ->
            // Skip system-protected items, symlinks and entries that became unavailable.
            val safeChild = runCatching { checked(child) }.getOrNull() ?: return@mapNotNull null
            if (child.absoluteFile != safeChild) return@mapNotNull null
            FileEntry(
                id = Uri.fromFile(safeChild).toString(),
                name = child.name,
                isDirectory = safeChild.isDirectory,
                sizeBytes = if (safeChild.isFile) safeChild.length() else 0L,
                modifiedAtMillis = safeChild.lastModified(),
                mimeType = if (safeChild.isDirectory) null else
                    android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(child.extension.lowercase()) ?: "application/octet-stream",
                canWrite = safeChild.canWrite()
            )
        }
    }

    fun canCreateFolder(folder: File): Boolean = checked(folder).canWrite()

    fun createFolder(parent: File, name: String) {
        validateRequestedName(name)
        val directory = checked(parent)
        check(directory.isDirectory && directory.canWrite()) { "Folder is read-only." }
        val target = checked(File(directory, name))
        rejectExistingName(target.exists())
        check(target.mkdir()) { "Could not create the folder." }
    }

    fun rename(file: File, newName: String) {
        validateRequestedName(newName)
        val source = checked(file)
        if (source.name == newName) return
        check(source.canWrite()) { "Item is read-only." }
        val target = checked(File(requireNotNull(source.parentFile), newName))
        rejectExistingName(target.exists())
        check(source.renameTo(target)) { "Could not rename the item." }
    }

    fun delete(file: File) {
        val target = checked(file)
        check(target.canWrite()) { "Item is read-only." }
        // No recursive delete in the MVP: prevents accidental removal of full folder trees.
        check(!target.isDirectory || target.listFiles()?.isEmpty() == true) {
            "This folder is not empty. Remove its contents first."
        }
        check(target.delete()) { "Could not delete the item." }
    }

    /** Review candidates only. Never scan inaccessible folders or delete during scanning. */
    fun scanLargeDownloads(maxVisited: Int = 10_000): Pair<List<CleanupCandidate>, Boolean> {
        val base = downloads()
        if (!base.isDirectory) return emptyList<CleanupCandidate>() to false
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        val candidates = ArrayList<CleanupCandidate>()
        stack.add(base)
        var examined = 0
        while (stack.isNotEmpty() && examined < maxVisited) {
            val next = stack.removeLast()
            // Avoid symlinks and traversal to paths outside Downloads.
            if (next.absoluteFile != next.canonicalFile) continue
            if (next != base && !next.canonicalPath.startsWith(base.canonicalPath + File.separator)) continue
            if (!visited.add(next.canonicalPath)) continue
            examined++
            if (next.isDirectory) {
                next.listFiles()?.forEach { stack.add(it) }
            } else if (next.isFile && next.canWrite() && isLargeDownload(next.length())) {
                candidates += CleanupCandidate(
                    id = Uri.fromFile(next).toString(),
                    path = next.relativeTo(base).invariantSeparatorsPath,
                    sizeBytes = next.length(),
                    modifiedAtMillis = next.lastModified()
                )
            }
        }
        return candidates.sortedByDescending { it.sizeBytes } to stack.isNotEmpty()
    }

    /** Revalidate each selected file at deletion time to avoid deleting a changed/replaced file. */
    fun deleteDownload(candidate: CleanupCandidate) {
        val base = downloads()
        val uri = Uri.parse(candidate.id)
        require(uri.scheme == "file") { "Invalid cleanup item." }
        val original = File(requireNotNull(uri.path))
        val target = checked(original)
        check(original.absoluteFile == target) { "Symlinks cannot be deleted from Cleanup." }
        check(target.path.startsWith(base.path + File.separator) && target.isFile) {
            "Only files inside Downloads can be cleaned up."
        }
        check(target.length() == candidate.sizeBytes && target.lastModified() == candidate.modifiedAtMillis) {
            "File changed since the scan. Scan again before deleting."
        }
        check(target.delete()) { "Could not delete " + target.name }
    }
}
