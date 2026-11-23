package com.meowmeow.filemanager.model

import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AnalysisKind { LARGE, DUPLICATES }

data class AnalysisItem<T>(
    val node: T,
    val name: String,
    val relativeFolder: String,
    val size: Long
)
data class DuplicateGroup<T>(
    val members: List<AnalysisItem<T>>,
    val fileSize: Long
) {
    val estimatedReclaimableBytes: Long get() =
        fileSize.coerceAtLeast(0) * (members.size - 1).toLong()
}
data class AnalysisOutcome<T>(
    val largeFiles: List<AnalysisItem<T>> = emptyList(),
    val duplicateGroups: List<DuplicateGroup<T>> = emptyList(),
    val scannedFiles: Int = 0,
    val scannedFolders: Int = 0,
    val unreadable: Int = 0,
    val truncated: Boolean = false,
    val hashedFiles: Int = 0,
    val reclaimableBytes: Long = 0
)

class FileAnalysisScanner<T>(private val ops: CopyOperations<T>) {
    private data class Pending<T>(val folder: T, val relativeFolder: String)
    private data class Sized<T>(val item: AnalysisItem<T>, val modified: Long)
    private data class Fingerprint(val bytes: List<Byte>)

    /**
     * Analysis is read-only, on demand, and strictly bounded.
     * The Android adapter enforces shared-storage / SAF grant boundaries.
     * Never label partial results as a complete scan.
     */
    suspend fun scan(
        root: T,
        kind: AnalysisKind,
        recursive: Boolean,
        minimumBytes: Long = 50L * 1024 * 1024,
        maxFolders: Int = 2_000,
        maxEntries: Int = 20_000,
        maxResults: Int = 300,
        maxHashBytes: Long = 20L * 1024 * 1024 * 1024,
        onProgress: suspend (folders: Int, entries: Int, hashed: Int) -> Unit = { _, _, _ -> }
    ): AnalysisOutcome<T> {
        require(minimumBytes >= 0 && maxFolders > 0 && maxEntries > 0 &&
            maxResults > 0 && maxHashBytes > 0)
        val queue = ArrayDeque<Pending<T>>()
        queue.add(Pending(root, ""))
        val seenFolders = hashSetOf<String>()
        val candidates = arrayListOf<Sized<T>>()
        var visitedFolders = 0
        var entries = 0
        var unreadable = 0
        var limited = false
        var hashed = 0

        scanLoop@ while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (visitedFolders >= maxFolders || entries >= maxEntries) {
                limited = true
                break
            }
            val next = queue.removeFirst()
            val identity = try { ops.identity(next.folder) }
                catch (_: Exception) { unreadable++; continue }
            if (!seenFolders.add(identity)) continue
            visitedFolders++
            val children = try { ops.children(next.folder) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unreadable++; onProgress(visitedFolders, entries, hashed); continue }
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (entries >= maxEntries) { limited = true; break@scanLoop }
                entries++
                try {
                    if (ops.isDirectory(child)) {
                        if (recursive) {
                            val name = ops.name(child)
                            val location = if (next.relativeFolder.isEmpty()) name
                                else next.relativeFolder + "/" + name
                            queue.add(Pending(child, location))
                        }
                    } else {
                        val size = ops.size(child)
                        if (size >= 0 &&
                            (kind == AnalysisKind.DUPLICATES || size >= minimumBytes)) {
                            candidates.add(Sized(
                                AnalysisItem(child, ops.name(child),
                                    next.relativeFolder.ifEmpty { "Selected scope" }, size),
                                ops.modified(child)
                            ))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unreadable++ }
                if (entries % 100 == 0) onProgress(visitedFolders, entries, hashed)
            }
            onProgress(visitedFolders, entries, hashed)
        }

        if (kind == AnalysisKind.LARGE) {
            val results = candidates.sortedWith(
                compareByDescending<Sized<T>> { it.item.size }.thenBy { it.item.name }
            )
            return AnalysisOutcome(
                largeFiles = results.take(maxResults).map { it.item },
                scannedFiles = entries, scannedFolders = visitedFolders,
                unreadable = unreadable, truncated = limited || results.size > maxResults
            )
        }

        val candidatesBySize = candidates.groupBy { it.item.size }
            .filterValues { it.size > 1 }
        val groups = arrayListOf<DuplicateGroup<T>>()
        var bytesHashed = 0L
        var resultsCount = 0
        var hashStopped = false
        sizeLoop@ for ((size, sized) in candidatesBySize) {
            currentCoroutineContext().ensureActive()
            val byHash = linkedMapOf<Fingerprint, MutableList<AnalysisItem<T>>>()
            for (candidate in sized) {
                currentCoroutineContext().ensureActive()
                if (size > maxHashBytes - bytesHashed) {
                    hashStopped = true
                    break@sizeLoop
                }
                try {
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var readBytes = 0L
                    ops.read(candidate.item.node).use { stream ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (n > 0) {
                                hash.update(buffer, 0, n)
                                readBytes += n
                            }
                        }
                    }
                    bytesHashed += readBytes
                    if (readBytes != size ||
                        (candidate.modified > 0 &&
                            ops.modified(candidate.item.node) != candidate.modified) ||
                        ops.size(candidate.item.node) != size
                    ) {
                        unreadable++ // changed while scanning: don't claim duplicate
                    } else {
                        val fingerprint = Fingerprint(hash.digest().toList())
                        byHash.getOrPut(fingerprint) { arrayListOf() }.add(candidate.item)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unreadable++ }
                hashed++
                if (hashed % 5 == 0) onProgress(visitedFolders, entries, hashed)
            }
            for (matched in byHash.values) {
                if (matched.size > 1) {
                    if (resultsCount + matched.size > maxResults) {
                        hashStopped = true
                        break@sizeLoop
                    }
                    groups.add(DuplicateGroup(matched.toList(), size))
                    resultsCount += matched.size
                }
            }
            onProgress(visitedFolders, entries, hashed)
        }
        val sorted = groups.sortedWith(compareByDescending<DuplicateGroup<T>> {
            it.estimatedReclaimableBytes
        }.thenByDescending { it.members.size })
        return AnalysisOutcome(
            duplicateGroups = sorted,
            scannedFiles = entries, scannedFolders = visitedFolders,
            unreadable = unreadable, truncated = limited || hashStopped,
            hashedFiles = hashed,
            reclaimableBytes = sorted.sumOf { it.estimatedReclaimableBytes }
        )
    }
}
