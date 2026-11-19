package com.meowmeow.filemanager.model

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Storage-specific implementation decides which folders are authorized. */
interface CopyOperations<T> {
    fun name(item: T): String
    fun isDirectory(item: T): Boolean
    fun mime(item: T): String?
    fun size(item: T): Long
    fun modified(item: T): Long
    fun identity(item: T): String
    fun children(folder: T): List<T>
    fun contains(folder: T, childName: String): Boolean
    /** Both creation methods must fail if an item already exists. */
    fun createFolder(parent: T, name: String): T
    fun createFile(parent: T, name: String, mime: String?): T
    fun read(file: T): InputStream
    fun write(file: T): OutputStream
    /** Delete only a newly created destination item, never the original. */
    fun removeCreated(item: T): Boolean
}

data class CopyTotals(val copiedFiles: Int, val copiedFolders: Int, val bytes: Long)

/**
 * Copy bytes with fixed-size buffers, verify every file's SHA-256 against
 * destination read-back and roll back the newly created top-level item if
 * any operation fails or is cancelled. No existing item is overwritten.
 */
class SafeCopy<T>(private val op: CopyOperations<T>) {
    suspend fun perform(
        source: T,
        destinationFolder: T,
        onProgress: suspend (CopyTotals) -> Unit = {}
    ): CopyTotals {
        currentCoroutineContext().ensureActive()
        if (!op.isDirectory(destinationFolder))
            throw ExpectedFileOperationException("Choose a destination folder.")
        val name = op.name(source)
        validateRequestedName(name)
        rejectExistingName(op.contains(destinationFolder, name))
        var created: T? = null
        var totals = CopyTotals(0, 0, 0L)
        var visited = 0
        val identifiers = hashSetOf<String>()
        try {
            // Only objects created below can ever be deleted on rollback.
            created = if (op.isDirectory(source))
                op.createFolder(destinationFolder, name)
            else op.createFile(destinationFolder, name, op.mime(source))

            suspend fun visit(input: T, output: T, depth: Int) {
                currentCoroutineContext().ensureActive()
                if (++visited > 10_000 || depth > 48)
                    throw IOException("Copy size/depth safety limit reached.")
                if (!identifiers.add(op.identity(input)))
                    throw IOException("Repeated folder reference in copy source.")
                if (op.isDirectory(input)) {
                    totals = totals.copy(copiedFolders = totals.copiedFolders + 1)
                    onProgress(totals)
                    for (child in op.children(input)) {
                        currentCoroutineContext().ensureActive()
                        val childName = op.name(child)
                        validateRequestedName(childName)
                        rejectExistingName(op.contains(output, childName))
                        val newChild = if (op.isDirectory(child))
                            op.createFolder(output, childName)
                        else op.createFile(output, childName, op.mime(child))
                        visit(child, newChild, depth + 1)
                    }
                } else {
                    val sizeBefore = op.size(input)
                    val modifiedBefore = op.modified(input)
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    op.read(input).use { sourceStream ->
                        op.write(output).use { destStream ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = sourceStream.read(buffer)
                                if (n == -1) break
                                destStream.write(buffer, 0, n)
                                hash.update(buffer, 0, n)
                                written += n
                            }
                            destStream.flush()
                        }
                    }
                    if (sizeBefore >= 0 && sizeBefore != written)
                        throw IOException("Source file changed during copy.")
                    if (modifiedBefore > 0 && op.modified(input) != modifiedBefore)
                        throw IOException("Source file changed during copy.")
                    val verify = MessageDigest.getInstance("SHA-256")
                    op.read(output).use { copied ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = copied.read(buffer)
                            if (n == -1) break
                            verify.update(buffer, 0, n)
                        }
                    }
                    if (!MessageDigest.isEqual(hash.digest(), verify.digest()))
                        throw IOException("Copy verification failed.")
                    totals = totals.copy(
                        copiedFiles = totals.copiedFiles + 1,
                        bytes = totals.bytes + written
                    )
                    onProgress(totals)
                }
            }
            visit(source, requireNotNull(created), 0)
            return totals
        } catch (failure: Throwable) {
            // A failed copy must not be reported as successful. The source is
            // never touched. If cleanup fails, warn that the destination may
            // contain an incomplete item instead of claiming rollback.
            val item = created
            if (item != null) {
                val cleared = try { op.removeCreated(item) } catch (_: Exception) { false }
                if (!cleared)
                    throw IOException("Incomplete copy may remain in destination. Review it before retrying.", failure)
            }
            throw failure
        }
    }
}
