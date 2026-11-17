package com.meowmeow.filemanager.model

import java.util.Locale

/** UI-safe snapshot; the DocumentFile handles stay in the storage layer. */
data class FileEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val mimeType: String?,
    val canWrite: Boolean
)

enum class SortMode(val label: String) {
    NAME("Name"),
    MODIFIED("Date modified"),
    SIZE("Size")
}

/** Folder-first sorting, stable and deterministic even when providers omit metadata. */
fun sortFiles(files: List<FileEntry>, mode: SortMode): List<FileEntry> {
    val secondary = when (mode) {
        SortMode.NAME -> compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }
        SortMode.MODIFIED -> compareByDescending<FileEntry> { it.modifiedAtMillis }
            .thenBy { it.name.lowercase(Locale.ROOT) }
        SortMode.SIZE -> compareByDescending<FileEntry> { it.sizeBytes }
            .thenBy { it.name.lowercase(Locale.ROOT) }
    }
    return files.sortedWith(compareBy<FileEntry> { !it.isDirectory }.then(secondary))
}

fun isValidFileName(name: String): Boolean =
    name.isNotBlank() && name == name.trim() &&
        name != "." && name != ".." &&
        '/' !in name && '\\' !in name && '\u0000' !in name
