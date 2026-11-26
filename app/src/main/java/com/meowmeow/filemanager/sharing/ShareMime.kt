package com.meowmeow.filemanager.sharing

import java.util.Locale

/**
 * Pure classifier: Android's SAF and local files may return null or the generic
 * octet-stream MIME. Correct MIME types make photo, mail and nearby-share targets
 * easier to discover. Unknown extensions are still shareable as generic files.
 */
fun shareMimeType(name: String, declared: String?): String {
    val supplied = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (!supplied.isNullOrBlank() && supplied != "*/*" &&
        supplied != "application/octet-stream") return supplied

    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "pdf" -> "application/pdf"
        "txt", "log", "md", "markdown" -> "text/plain"
        "json", "jsonl", "ndjson" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "html", "htm" -> "text/html"
        "yaml", "yml" -> "text/yaml"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> "*/*"
    }
}
