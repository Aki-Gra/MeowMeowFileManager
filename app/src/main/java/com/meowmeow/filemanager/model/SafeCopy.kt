package com.meowmeow.filemanager.model

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
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

    /** Replacement must be an atomic, same-directory swap; never truncate the old file. */
    fun supportsAtomicReplace(folder: T): Boolean = false
    fun findChild(folder: T, name: String): T? =
        children(folder).firstOrNull { this.name(it) == name }
    fun atomicReplace(staged: T, existing: T) {
        throw ExpectedFileOperationException("Safe replacement is not available in this folder.")
    }
}

data class CopyTotals(
    val copiedFiles: Int,
    val copiedFolders: Int,
    val bytes: Long,
    val replacedExisting: Boolean = false,
    val renamedTo: String? = null
)

/**
 * Copy bytes with fixed-size buffers, verify every file's SHA-256 against
 * destination read-back and roll back only newly created items if an operation
 * fails. Explicit replacement uses a verified staged copy and atomic swap.
 */
class SafeCopy<T>(private val op: CopyOperations<T>) {
    private suspend fun calculateHash(item: T): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        op.read(item).use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    suspend fun perform(
        source: T,
        destinationFolder: T,
        resolveConflict: suspend (CopyConflict) -> CopyConflictChoice = { CopyConflictChoice.Skip },
        onProgress: suspend (CopyTotals) -> Unit = {}
    ): CopyTotals {
        currentCoroutineContext().ensureActive()
        if (!op.isDirectory(destinationFolder))
            throw ExpectedFileOperationException("Choose a destination folder.")
        val originalName = op.name(source)
        validateRequestedName(originalName)
        var selectedName = originalName
        var replaceTarget: T? = null
        var oldHash: ByteArray? = null
        var oldSize = -1L
        var oldModified = 0L
        var prompts = 0
        while (op.contains(destinationFolder, selectedName) && replaceTarget == null) {
            if (++prompts > 10)
                throw ExpectedFileOperationException("Too many name conflicts. Try another folder.")
            val item = op.findChild(destinationFolder, selectedName)
            val allowReplace = item != null && !op.isDirectory(source) &&
                !op.isDirectory(item) && op.supportsAtomicReplace(destinationFolder) &&
                op.identity(item) != op.identity(source)
            when (val choice = resolveConflict(CopyConflict(
                selectedName, suggestCopyName(selectedName) { op.contains(destinationFolder, it) },
                allowReplace
            ))) {
                CopyConflictChoice.Skip ->
                    throw ExpectedFileOperationException("Copy skipped; destination name already exists.")
                is CopyConflictChoice.Rename -> {
                    validateRequestedName(choice.newName)
                    selectedName = choice.newName
                    // A manually entered name may itself already exist.
                    // Re-prompt instead of unexpectedly ending the copy.
                }
                CopyConflictChoice.Replace -> {
                    if (!allowReplace || item == null)
                        throw ExpectedFileOperationException(
                            "Safe replacement is unavailable. Rename the copy instead."
                        )
                    replaceTarget = item
                    oldSize = op.size(item)
                    oldModified = op.modified(item)
                    oldHash = calculateHash(item)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val stagedName = if (replaceTarget == null) selectedName else {
            val ext = selectedName.substringAfterLast('.', "")
                .takeIf { selectedName.lastIndexOf('.') > 0 && it.isNotEmpty() }?.let { ".$it" } ?: ""
            var candidate: String
            do {
                candidate = "meowmeow-staging-" + UUID.randomUUID().toString() + ext
            } while (op.contains(destinationFolder, candidate))
            candidate
        }
        var created: T? = null
        // Track exactly what this operation created. Never recursively delete
        // unrelated files that appeared in the destination while copying.
        val createdItems = arrayListOf<T>()
        var totals = CopyTotals(0, 0, 0L)
        var visited = 0
        val identifiers = hashSetOf<String>()
        try {
            // Only objects created below can ever be deleted on rollback.
            created = if (op.isDirectory(source))
                op.createFolder(destinationFolder, stagedName)
            else op.createFile(destinationFolder, stagedName, op.mime(source))
            createdItems.add(requireNotNull(created))

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
                        createdItems.add(newChild)
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
            replaceTarget?.let { existing ->
                currentCoroutineContext().ensureActive()
                // Refuse stale confirmation: replacing a newly changed target
                // could destroy data the user never intended to replace.
                val current = op.findChild(destinationFolder, selectedName)
                if (current == null || op.identity(current) != op.identity(existing) ||
                    (oldSize >= 0 && op.size(current) != oldSize) ||
                    (oldModified > 0 && op.modified(current) != oldModified) ||
                    !MessageDigest.isEqual(oldHash, calculateHash(current))
                ) throw ExpectedFileOperationException(
                    "Destination changed since confirmation. Review it and retry."
                )
                currentCoroutineContext().ensureActive()
                op.atomicReplace(requireNotNull(created), current)
            }
            return totals.copy(
                replacedExisting = replaceTarget != null,
                renamedTo = selectedName.takeIf { it != originalName }
            )
        } catch (failure: Throwable) {
            // A failed copy must not be reported as successful. The source is
            // never touched. If cleanup fails, warn that the destination may
            // contain an incomplete item instead of claiming rollback.
            var cleaned = true
            for (item in createdItems.asReversed()) {
                val removed = try { op.removeCreated(item) } catch (_: Exception) { false }
                if (!removed) cleaned = false
            }
            if (!cleaned)
                throw IOException("Incomplete copy may remain in destination. Review it before retrying.", failure)
            throw failure
        }
    }
}
