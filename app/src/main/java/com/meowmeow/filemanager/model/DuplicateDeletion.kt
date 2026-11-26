package com.meowmeow.filemanager.model

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A snapshot from a verified duplicate scan. Never trust an ID or hash passed
 * from UI: construct these targets only from the original in-memory results.
 */
data class DuplicateDeleteTarget<T>(
    val node: T,
    val identity: String,
    val name: String,
    val size: Long,
    val modifiedAtMillis: Long,
    val sha256: List<Byte>
)

enum class DuplicateDeleteStatus { DELETED, CHANGED, FAILED }
data class DuplicateDeleteItemResult(
    val identity: String,
    val status: DuplicateDeleteStatus
)
data class DuplicateDeleteSummary(
    val total: Int,
    val completed: List<DuplicateDeleteItemResult> = emptyList()
) {
    val deleted: Int get() = completed.count { it.status == DuplicateDeleteStatus.DELETED }
    val changed: Int get() = completed.count { it.status == DuplicateDeleteStatus.CHANGED }
    val failed: Int get() = completed.count { it.status == DuplicateDeleteStatus.FAILED }
    val remaining: Int get() = total - completed.size
}

/** Permanent deletion of exact files only, with per-file SHA-256 verification. */
class VerifiedDuplicateDeletion<T>(private val operations: BulkOperations<T>) {
    suspend fun delete(
        selection: List<DuplicateDeleteTarget<T>>,
        onProgress: suspend (DuplicateDeleteSummary) -> Unit = {}
    ): DuplicateDeleteSummary {
        require(selection.isNotEmpty() && selection.size <= 300)
        require(selection.map { it.identity }.distinct().size == selection.size)
        var summary = DuplicateDeleteSummary(total = selection.size)
        for (target in selection) {
            currentCoroutineContext().ensureActive()
            val status = try {
                if (!stillMatches(target)) {
                    DuplicateDeleteStatus.CHANGED
                } else if (operations.deleteSource(target.node)) {
                    DuplicateDeleteStatus.DELETED
                } else {
                    DuplicateDeleteStatus.FAILED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                DuplicateDeleteStatus.FAILED
            } catch (_: Exception) {
                DuplicateDeleteStatus.FAILED
            }
            summary = summary.copy(
                completed = summary.completed + DuplicateDeleteItemResult(target.identity, status)
            )
            onProgress(summary)
        }
        return summary
    }

    private suspend fun stillMatches(target: DuplicateDeleteTarget<T>): Boolean {
        currentCoroutineContext().ensureActive()
        if (target.sha256.size != 32 || target.size < 0L ||
            operations.identity(target.node) != target.identity ||
            operations.isDirectory(target.node) ||
            operations.name(target.node) != target.name ||
            operations.size(target.node) != target.size) return false
        val modified = operations.modified(target.node)
        if (target.modifiedAtMillis > 0L && modified != target.modifiedAtMillis) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytesRead = 0L
        operations.read(target.node).use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) {
                    bytesRead += n
                    if (bytesRead > target.size) return false
                    digest.update(buffer, 0, n)
                }
            }
        }
        if (bytesRead != target.size ||
            !MessageDigest.isEqual(digest.digest(), target.sha256.toByteArray()))
            return false
        // Re-check metadata immediately before deleting the exact file.
        return operations.identity(target.node) == target.identity &&
            !operations.isDirectory(target.node) &&
            operations.name(target.node) == target.name &&
            operations.size(target.node) == target.size &&
            (target.modifiedAtMillis <= 0L ||
                operations.modified(target.node) == target.modifiedAtMillis)
    }
}
