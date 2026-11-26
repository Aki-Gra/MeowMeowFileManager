package com.meowmeow.filemanager.model

import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Classification is based on metadata only; no file contents are read. */
enum class FileKind(val label: String) {
    ALL("All types"), IMAGES("Images"), VIDEOS("Videos"), AUDIO("Audio"),
    DOCUMENTS("Documents"), ARCHIVES("Archives"), APKS("APKs"), OTHER("Other")
}

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tif", "tiff", "avif")
private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "wmv", "mpeg")
private val audioExt = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma", "mid")
private val documentExt = setOf("txt", "pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx",
    "csv", "ppt", "pptx", "md", "json", "xml", "html", "epub")
private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst")

fun detectFileKind(entry: FileEntry): FileKind {
    if (entry.isDirectory) return FileKind.OTHER
    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mime = entry.mimeType?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim().orEmpty()
    if (ext == "apk" || mime == "application/vnd.android.package-archive") return FileKind.APKS
    if (mime.startsWith("image/")) return FileKind.IMAGES
    if (mime.startsWith("video/")) return FileKind.VIDEOS
    if (mime.startsWith("audio/")) return FileKind.AUDIO
    if (mime in setOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/x-tar", "application/gzip", "application/x-gzip", "application/x-bzip2",
            "application/vnd.rar", "application/x-zip-compressed", "application/x-compressed-tar"))
        return FileKind.ARCHIVES
    if (mime.startsWith("text/") ||
        mime in setOf("application/pdf", "application/json", "application/xml", "application/epub+zip") ||
        mime.startsWith("application/vnd.openxmlformats-officedocument.") ||
        mime.startsWith("application/vnd.oasis.opendocument.") ||
        mime in setOf("application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint"))
        return FileKind.DOCUMENTS
    // MIME may be missing or too generic to be helpful; use known extensions then.
    if (mime.isEmpty() || mime in setOf("application/octet-stream", "*/*", "binary/octet-stream")) {
        return when (ext) {
            in imageExt -> FileKind.IMAGES
            in videoExt -> FileKind.VIDEOS
            in audioExt -> FileKind.AUDIO
            in documentExt -> FileKind.DOCUMENTS
            in archiveExt -> FileKind.ARCHIVES
            else -> FileKind.OTHER
        }
    }
    return FileKind.OTHER
}

fun matchesFileSearch(entry: FileEntry, query: String, kind: FileKind): Boolean {
    if (!entry.name.contains(query.trim(), ignoreCase = true)) return false
    if (kind == FileKind.ALL) return true
    return !entry.isDirectory && detectFileKind(entry) == kind
}

/** A search result carries a folder trail, never an unrestricted filesystem reference. */
data class DiscoveryMatch<T>(
    val entry: FileEntry,
    val location: String,
    val folderTrail: List<T>
)

data class DiscoveryOutcome<T>(
    val matches: List<DiscoveryMatch<T>>,
    val foldersVisited: Int,
    val entriesVisited: Int,
    val unreadableFolders: Int,
    val truncated: Boolean
)

/**
 * Bounded, cancellable BFS. Backend must only return children of an authorized
 * folder, never symlinks or protected directories; this scanner grants no access.
 */
class FileDiscoveryScanner<T>(
    private val identity: (T) -> String,
    private val listChildren: (T) -> List<Pair<FileEntry, T>>
) {
    private data class Node<T>(val folder: T, val trail: List<T>, val location: String)

    suspend fun scan(
        root: T,
        recursive: Boolean,
        query: String,
        kind: FileKind,
        maxFolders: Int = 2_000,
        maxEntries: Int = 20_000,
        maxResults: Int = 300,
        onProgress: suspend (folders: Int, entries: Int) -> Unit = { _, _ -> }
    ): DiscoveryOutcome<T> {
        require(maxFolders > 0 && maxEntries > 0 && maxResults > 0)
        val pending = ArrayDeque<Node<T>>()
        pending.add(Node(root, listOf(root), ""))
        val visited = hashSetOf<String>()
        val results = arrayListOf<DiscoveryMatch<T>>()
        var scanned = 0
        var unreadable = 0
        var truncated = false
        var examined = 0

        scanLoop@ while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (scanned >= maxFolders || examined >= maxEntries) {
                truncated = true
                break
            }
            val node = pending.removeFirst()
            if (!visited.add(identity(node.folder))) continue
            scanned++
            val contents = try {
                listChildren(node.folder)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unreadable++
                onProgress(scanned, examined)
                continue
            }
            for ((entry, target) in contents) {
                currentCoroutineContext().ensureActive()
                if (examined >= maxEntries) {
                    truncated = true
                    break@scanLoop
                }
                examined++
                if (matchesFileSearch(entry, query, kind)) {
                    if (results.size >= maxResults) {
                        truncated = true
                        break@scanLoop
                    }
                    results.add(DiscoveryMatch(
                        entry,
                        if (node.location.isEmpty()) "Selected scope" else node.location,
                        if (entry.isDirectory) node.trail + target else node.trail
                    ))
                }
                if (recursive && entry.isDirectory) {
                    pending.add(Node(
                        target, node.trail + target,
                        if (node.location.isEmpty()) entry.name else node.location + "/" + entry.name
                    ))
                }
                if (examined % 100 == 0) onProgress(scanned, examined)
            }
            onProgress(scanned, examined)
        }
        return DiscoveryOutcome(results, scanned, examined, unreadable, truncated)
    }
}
