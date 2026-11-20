package com.meowmeow.filemanager.model

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class BulkAction { COPY, MOVE, DELETE }
enum class BulkStatus { SUCCESS, SKIPPED, FAILED }
data class BulkSelection<T>(val item: T, val displayName: String)
data class BulkItemResult(val name: String, val status: BulkStatus, val detail: String)
data class BulkProgress(
    val total: Int,
    val processed: Int = 0,
    val success: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytesCopied: Long = 0,
    val filesCopied: Int = 0,
    val results: List<BulkItemResult> = emptyList()
)

/** Restrict deletion to objects still within the user's authorized tree. */
interface BulkOperations<T> : CopyOperations<T> {
    /** Refuses nonempty folders. No recursive deletion in storage adapter. */
    fun deleteSource(item: T): Boolean
}

private data class ItemSnapshot<T>(
    val item: T,
    val id: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val descendants: List<ItemSnapshot<T>>,
    val digest: ByteArray?
)

/**
 * Sequential, cancellable bulk actions. Every top-level item produces a
 * success/skip/failure result. MOVE first streams and verifies the full copy,
 * then revalidates source and destination trees before source deletion.
 * DELETE checks the tree before removing any of its contents.
 * Never delete source data just because the destination write was attempted.
 */
class SafeBulkOperations<T>(private val ops: BulkOperations<T>) {
    suspend fun execute(
        action: BulkAction,
        selected: List<BulkSelection<T>>,
        destination: T? = null,
        validateDestination: (T, T) -> Unit = { _, _ -> },
        onUpdate: suspend (BulkProgress) -> Unit = {}
    ): BulkProgress {
        require(selected.isNotEmpty() && selected.size <= 500)
        if (action != BulkAction.DELETE && destination == null)
            throw ExpectedFileOperationException("Choose a destination.")
        var progress = BulkProgress(total = selected.size)
        val seen = hashSetOf<String>()
        for (selection in selected) {
            currentCoroutineContext().ensureActive()
            val id = ops.identity(selection.item)
            if (!seen.add(id)) continue // never operate on the same selected item twice
            var copiedBytes = 0L
            var copiedFiles = 0
            val result = try {
                when (action) {
                    BulkAction.COPY -> {
                        val folder = requireNotNull(destination)
                        validateDestination(selection.item, folder)
                        val copied = SafeCopy(ops).perform(selection.item, folder)
                        copiedBytes = copied.bytes
                        copiedFiles = copied.copiedFiles
                        BulkItemResult(selection.displayName, BulkStatus.SUCCESS, "Copied")
                    }
                    BulkAction.MOVE -> {
                        val folder = requireNotNull(destination)
                        validateDestination(selection.item, folder)
                        val before = snapshot(selection.item, hashFiles = true)
                        val copied = SafeCopy(ops).perform(selection.item, folder)
                        copiedBytes = copied.bytes
                        copiedFiles = copied.copiedFiles
                        // Do not touch the source if either copy or verification failed.
                        try {
                            validateSnapshot(before, hashFiles = true)
                            val copiedRoot = ops.children(folder).singleOrNull {
                                ops.name(it) == ops.name(selection.item)
                            } ?: throw IOException("Copied destination no longer available.")
                            verifyMatchingTrees(before, copiedRoot)
                            deleteTree(before)
                            BulkItemResult(selection.displayName, BulkStatus.SUCCESS, "Moved")
                        } catch (failure: Exception) {
                            // Do not roll back a verified copy here: a source might have
                            // been partly removed. Keep both locations for user review.
                            throw IOException(
                                "Move copy exists; source may also remain. Review both locations.", failure
                            )
                        }
                    }
                    BulkAction.DELETE -> {
                        val before = snapshot(selection.item, hashFiles = false)
                        validateSnapshot(before, hashFiles = false)
                        deleteTree(before)
                        BulkItemResult(selection.displayName, BulkStatus.SUCCESS, "Deleted permanently")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: ExpectedFileOperationException) {
                BulkItemResult(selection.displayName, BulkStatus.SKIPPED,
                    expected.message ?: "Skipped due to a validation conflict")
            } catch (failure: Exception) {
                BulkItemResult(selection.displayName, BulkStatus.FAILED,
                    if (failure.message?.startsWith("Move copy exists") == true)
                        "Move may be partial. Check source and destination."
                    else if (failure.message?.startsWith("Incomplete copy") == true)
                        "Incomplete destination may remain; check it."
                    else "Operation failed. Check the selected item.")
            }
            progress = progress.copy(
                processed = progress.processed + 1,
                success = progress.success + if (result.status == BulkStatus.SUCCESS) 1 else 0,
                skipped = progress.skipped + if (result.status == BulkStatus.SKIPPED) 1 else 0,
                failed = progress.failed + if (result.status == BulkStatus.FAILED) 1 else 0,
                bytesCopied = progress.bytesCopied + copiedBytes,
                filesCopied = progress.filesCopied + copiedFiles,
                results = progress.results + result
            )
            onUpdate(progress)
        }
        return progress
    }

    private suspend fun hash(item: T): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(64 * 1024)
        ops.read(item).use { source ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = source.read(bytes)
                if (n < 0) break
                if (n > 0) digest.update(bytes, 0, n)
            }
        }
        return digest.digest()
    }

    private suspend fun snapshot(
        item: T, hashFiles: Boolean, depth: Int = 0,
        seen: MutableSet<String> = hashSetOf(),
        visited: IntArray = intArrayOf(0)
    ): ItemSnapshot<T> {
        currentCoroutineContext().ensureActive()
        if (depth > 48 || ++visited[0] > 10_000)
            throw IOException("Folder size/depth safety limit reached.")
        val id = ops.identity(item)
        if (!seen.add(id)) throw IOException("Repeated folder reference.")
        val folder = ops.isDirectory(item)
        val size = if (folder) 0L else ops.size(item)
        val modified = ops.modified(item)
        val children = if (folder) ops.children(item).map {
            snapshot(it, hashFiles, depth + 1, seen, visited)
        } else emptyList()
        return ItemSnapshot(item, id, ops.name(item), folder,
            size, modified, children, if (!folder && hashFiles) hash(item) else null)
    }

    private suspend fun validateSnapshot(s: ItemSnapshot<T>, hashFiles: Boolean) {
        currentCoroutineContext().ensureActive()
        if (ops.identity(s.item) != s.id || ops.isDirectory(s.item) != s.directory ||
            ops.name(s.item) != s.name) throw IOException("Selected item changed.")
        if (s.directory) {
            val actual = ops.children(s.item).map { ops.identity(it) }.toSet()
            if (actual.size != s.descendants.size ||
                actual != s.descendants.map { it.id }.toSet())
                throw IOException("Folder contents changed. Rescan before deleting.")
            for (child in s.descendants) validateSnapshot(child, hashFiles)
        } else {
            if (s.size >= 0 && ops.size(s.item) != s.size ||
                s.modified > 0 && ops.modified(s.item) != s.modified)
                throw IOException("Selected item changed.")
            if (hashFiles && !MessageDigest.isEqual(s.digest, hash(s.item)))
                throw IOException("Source changed since copy.")
        }
    }

    private suspend fun verifyMatchingTrees(s: ItemSnapshot<T>, target: T) {
        currentCoroutineContext().ensureActive()
        if (ops.name(target) != s.name || ops.isDirectory(target) != s.directory)
            throw IOException("Destination differs from source.")
        if (s.directory) {
            val actual = ops.children(target)
            if (actual.size != s.descendants.size ||
                actual.map { ops.name(it) }.toSet() != s.descendants.map { it.name }.toSet())
                throw IOException("Copied folder differs from source.")
            for (child in s.descendants) {
                val dst = actual.single { ops.name(it) == child.name }
                verifyMatchingTrees(child, dst)
            }
        } else if (s.digest != null &&
            !MessageDigest.isEqual(s.digest, hash(target))) {
            throw IOException("Destination content differs from source.")
        }
    }

    private suspend fun deleteTree(s: ItemSnapshot<T>) {
        currentCoroutineContext().ensureActive()
        // Revalidate each subtree immediately before touching it.
        validateSnapshot(s, hashFiles = s.digest != null)
        for (child in s.descendants) {
            deleteTree(child)
        }
        // Only remove the now-empty directory or exact file, never a full
        // recursive folder in one provider operation.
        if (!ops.deleteSource(s.item))
            throw IOException("Could not remove selected item.")
    }
}
