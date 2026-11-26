package com.meowmeow.filemanager.ui

import com.meowmeow.filemanager.model.FileEntry

data class FileSearchHit(val entry: FileEntry, val location: String)

data class FileSearchState(
    val isRunning: Boolean = false,
    val finished: Boolean = false,
    val hits: List<FileSearchHit> = emptyList(),
    val foldersVisited: Int = 0,
    val entriesVisited: Int = 0,
    val unreadableFolders: Int = 0,
    val truncated: Boolean = false,
    val notice: String? = null
)
