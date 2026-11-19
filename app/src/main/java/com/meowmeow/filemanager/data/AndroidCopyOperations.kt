package com.meowmeow.filemanager.data

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.meowmeow.filemanager.model.CopyOperations
import com.meowmeow.filemanager.model.ExpectedFileOperationException
import com.meowmeow.filemanager.model.rejectExistingName
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale

sealed class CopyNode {
    data class Device(val file: File) : CopyNode()
    data class Scoped(val file: DocumentFile) : CopyNode()
}

/** Trusted copy handle constructed from an existing explorer entry or folder. */
data class CopyPlace(
    val node: CopyNode,
    val rootKey: String,
    val ancestors: List<String>
) {
    val identity: String get() = when (node) {
        is CopyNode.Device -> node.file.path
        is CopyNode.Scoped -> node.file.uri.toString()
    }
}

class AndroidCopyOperations(context: Context) : CopyOperations<CopyNode> {
    private val resolver = context.applicationContext.contentResolver
    private val local = LocalFileRepository()

    private fun checked(n: CopyNode.Device): File = local.checkedForCopy(n.file)
    private fun doc(n: CopyNode.Scoped): DocumentFile =
        n.file.takeIf { it.exists() } ?: throw IOException("Selected document is no longer accessible.")

    override fun name(item: CopyNode): String = when (item) {
        is CopyNode.Device -> checked(item).name
        is CopyNode.Scoped -> doc(item).name ?: throw IOException("Document name unavailable.")
    }

    override fun isDirectory(item: CopyNode): Boolean = when (item) {
        is CopyNode.Device -> checked(item).isDirectory
        is CopyNode.Scoped -> doc(item).isDirectory
    }

    override fun mime(item: CopyNode): String? = when (item) {
        is CopyNode.Device -> {
            val ext = checked(item).extension.lowercase(Locale.ROOT)
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        is CopyNode.Scoped -> doc(item).type ?: "application/octet-stream"
    }

    override fun size(item: CopyNode): Long = when (item) {
        is CopyNode.Device -> checked(item).length()
        is CopyNode.Scoped -> doc(item).length().takeIf { it > 0L } ?: -1L
    }

    override fun modified(item: CopyNode): Long = when (item) {
        is CopyNode.Device -> checked(item).lastModified()
        is CopyNode.Scoped -> doc(item).lastModified()
    }

    override fun identity(item: CopyNode): String = when (item) {
        is CopyNode.Device -> "file:" + checked(item).path
        is CopyNode.Scoped -> "doc:" + doc(item).uri
    }

    override fun children(folder: CopyNode): List<CopyNode> = when (folder) {
        is CopyNode.Device -> {
            val dir = checked(folder)
            if (!dir.isDirectory) throw IOException("Copy source is not a folder.")
            val found = dir.listFiles() ?: throw IOException("Cannot read a source folder.")
            // Never silently skip protected entries or symlinks in a folder copy.
            found.map { CopyNode.Device(local.checkedForCopy(it)) }
        }
        is CopyNode.Scoped -> {
            val dir = doc(folder)
            if (!dir.isDirectory || !dir.canRead()) throw IOException("Cannot read a source folder.")
            dir.listFiles().map { CopyNode.Scoped(it) }
        }
    }

    override fun contains(folder: CopyNode, childName: String): Boolean = when (folder) {
        is CopyNode.Device -> {
            val dir = checked(folder)
            if (!dir.isDirectory) throw IOException("Choose a folder.")
            Files.exists(File(dir, childName).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        is CopyNode.Scoped -> doc(folder).findFile(childName) != null
    }

    override fun createFolder(parent: CopyNode, name: String): CopyNode {
        rejectExistingName(contains(parent, name))
        return when (parent) {
            is CopyNode.Device -> {
                val parentDir = checked(parent)
                if (!parentDir.canWrite()) throw IOException("Destination is read-only.")
                val created = File(parentDir, name)
                Files.createDirectory(created.toPath()) // fails if destination appears concurrently
                CopyNode.Device(local.checkedForCopy(created))
            }
            is CopyNode.Scoped -> {
                val parentDoc = doc(parent)
                if (!parentDoc.canWrite()) throw IOException("Destination is read-only.")
                val result = parentDoc.createDirectory(name)
                    ?: throw IOException("Could not create the destination folder.")
                if (result.name != name) {
                    result.delete()
                    throw IOException("Provider changed the folder name. Copy stopped.")
                }
                CopyNode.Scoped(result)
            }
        }
    }

    override fun createFile(parent: CopyNode, name: String, mime: String?): CopyNode {
        rejectExistingName(contains(parent, name))
        return when (parent) {
            is CopyNode.Device -> {
                val parentDir = checked(parent)
                if (!parentDir.canWrite()) throw IOException("Destination is read-only.")
                val created = File(parentDir, name)
                Files.createFile(created.toPath()) // atomic CREATE_NEW, never overwrite
                CopyNode.Device(local.checkedForCopy(created))
            }
            is CopyNode.Scoped -> {
                val parentDoc = doc(parent)
                if (!parentDoc.canWrite()) throw IOException("Destination is read-only.")
                val result = parentDoc.createFile(mime ?: "application/octet-stream", name)
                    ?: throw IOException("Could not create the destination file.")
                if (result.name != name) {
                    result.delete()
                    throw IOException("Provider changed the filename. Copy stopped.")
                }
                CopyNode.Scoped(result)
            }
        }
    }

    override fun read(file: CopyNode): InputStream = when (file) {
        is CopyNode.Device -> {
            val selected = checked(file)
            if (!selected.isFile) throw IOException("Source file unavailable.")
            Files.newInputStream(selected.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        }
        is CopyNode.Scoped -> {
            val selected = doc(file)
            if (!selected.isFile || !selected.canRead()) throw IOException("Source file unavailable.")
            resolver.openInputStream(selected.uri) ?: throw IOException("Could not read the file.")
        }
    }

    override fun write(file: CopyNode): OutputStream = when (file) {
        is CopyNode.Device -> {
            val selected = checked(file)
            Files.newOutputStream(selected.toPath(),
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        }
        is CopyNode.Scoped -> {
            val selected = doc(file)
            resolver.openOutputStream(selected.uri, "wt")
                ?: throw IOException("Could not write the destination file.")
        }
    }

    override fun removeCreated(item: CopyNode): Boolean = when (item) {
        is CopyNode.Device -> {
            // Delete exactly the path created by the copy, never recursively walk it.
            val file = local.checkedForCopy(item.file)
            Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.deleteIfExists(file.toPath())
        }
        is CopyNode.Scoped -> {
            val file = item.file
            // SAF exists() can return false when permission is revoked,
            // not only when the file was removed. Never claim cleanup success
            // unless the provider confirms deletion.
            if (!file.exists()) false
            else if (file.isDirectory && file.listFiles().isNotEmpty()) false
            else file.delete()
        }
    }
}

/** Keep folder copies inside one authorization tree; cross-mode file copy is supported. */
fun validateCopyDestination(source: CopyPlace, dest: CopyPlace, isFolder: Boolean) {
    if (source.identity == dest.identity && source.node::class == dest.node::class) {
        throw ExpectedFileOperationException("Choose a different destination folder.")
    }
    if (!isFolder) return
    if (source.node::class != dest.node::class || source.rootKey != dest.rootKey) {
        throw ExpectedFileOperationException(
            "To copy a folder, choose a destination in the same storage mode and selected root."
        )
    }
    val inside = when {
        source.node is CopyNode.Device && dest.node is CopyNode.Device -> {
            val parentPath = source.node.file.canonicalPath
            val destPath = dest.node.file.canonicalPath
            destPath == parentPath || destPath.startsWith(parentPath + File.separator)
        }
        else -> source.identity in dest.ancestors
    }
    if (inside) {
        throw ExpectedFileOperationException(
            "A folder cannot be copied into itself or one of its subfolders."
        )
    }
}
