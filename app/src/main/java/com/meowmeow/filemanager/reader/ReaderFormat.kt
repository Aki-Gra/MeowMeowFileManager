package com.meowmeow.filemanager.reader

import java.util.Locale

/** Supported content is always opened inside MeowMeow first. */
enum class ReaderFormat {
    PDF, TEXT, DOCX, DOC, ODT, UNSUPPORTED;

    companion object {
        private val plainTextExtensions = setOf(
            "txt", "text", "json", "jsonl", "ndjson", "xml", "csv", "tsv",
            "md", "markdown", "log", "yaml", "yml", "ini", "cfg", "conf",
            "properties", "html", "htm", "css", "js", "ts", "kt", "java",
            "py", "sh", "sql", "gradle", "kts", "toml"
        )

        fun detect(name: String, mime: String?): ReaderFormat {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when (extension) {
                "pdf" -> PDF
                "docx" -> DOCX
                "doc" -> DOC
                "odt" -> ODT
                in plainTextExtensions -> TEXT
                else -> when (mime?.lowercase(Locale.ROOT)?.substringBefore(';')) {
                    "application/pdf" -> PDF
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX
                    "application/msword" -> DOC
                    "application/vnd.oasis.opendocument.text" -> ODT
                    "text/plain", "application/json", "application/xml", "text/xml",
                    "text/csv", "text/markdown", "application/x-yaml" -> TEXT
                    else -> if (mime?.startsWith("text/", ignoreCase = true) == true) TEXT else UNSUPPORTED
                }
            }
        }
    }
}

/** Large and unknown/unsupported files must not be read into unlimited RAM. */
internal const val MAX_TEXT_BYTES = 5 * 1024 * 1024
internal const val MAX_WORD_BYTES = 20 * 1024 * 1024
internal const val MAX_PDF_BYTES = 75L * 1024L * 1024L
internal const val MAX_RENDER_WIDTH = 1800
internal const val MAX_RENDER_HEIGHT = 2600

fun readerUnsupportedMessage(format: ReaderFormat): String = when (format) {
    ReaderFormat.UNSUPPORTED -> "MeowMeow does not have a built-in reader for this file type."
    else -> "Unable to read this document."
}

/** Distinct from unexpected I/O failures; file limits are a deliberate safety policy. */
class ReaderLimitException(message: String) : Exception(message)
