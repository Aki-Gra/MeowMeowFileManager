package com.meowmeow.filemanager.reader

import com.meowmeow.filemanager.model.FileEntry
import java.util.UUID

/**
 * One-shot, in-process image navigation context from MeowMeow's Files/Search.
 * No disk writes, broadcasts, broad scanning, or external gallery enumeration.
 * Externally opened images never receive a gallery token.
 */
object ImageGallerySessions {
    private const val MAX_IMAGES = 5000
    private const val MAX_SESSIONS = 2

    data class Session(val images: List<FileEntry>, val startIndex: Int)

    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized
    fun create(opened: FileEntry, orderedEntries: List<FileEntry>): String? {
        if (!isImage(opened)) return null
        val images = orderedEntries.asSequence().filter(::isImage)
            .distinctBy { it.id }.toList()
        val selected = images.indexOfFirst { it.id == opened.id }
        if (images.size < 2 || selected < 0) return null

        // Very large folders are windowed around the opened image to avoid
        // passing huge lists through Android intents or retaining unbounded RAM.
        val start = (selected - MAX_IMAGES / 2).coerceAtLeast(0)
            .coerceAtMost((images.size - MAX_IMAGES).coerceAtLeast(0))
        val window = images.subList(start, minOf(start + MAX_IMAGES, images.size)).toList()
        val token = UUID.randomUUID().toString()
        sessions[token] = Session(window, selected - start)
        while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.first())
        return token
    }

    @Synchronized
    fun get(token: String?): Session? = if (token == null) null else sessions[token]

    @Synchronized
    fun remove(token: String?) {
        if (token != null) sessions.remove(token)
    }

    private fun isImage(entry: FileEntry) =
        !entry.isDirectory && ReaderFormat.detect(entry.name, entry.mimeType) == ReaderFormat.IMAGE
}
