package com.meowmeow.filemanager.model

const val LARGE_DOWNLOAD_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L

data class CleanupCandidate(
    val id: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long
)

fun isLargeDownload(sizeBytes: Long): Boolean = sizeBytes >= LARGE_DOWNLOAD_THRESHOLD_BYTES

fun humanSize(size: Long): String {
    if (size < 1024L) return size.toString() + " B"
    var value = size.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < 4) { value /= 1024.0; unit++ }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value,
        listOf("B", "KB", "MB", "GB", "TB")[unit])
}
