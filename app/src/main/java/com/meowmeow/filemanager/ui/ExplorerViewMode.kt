package com.meowmeow.filemanager.ui

/**
 * An experimental layout preference, not a storage setting. It never records
 * filenames or scan results. The existing list remains the default.
 */
enum class ExplorerViewMode(val label: String) {
    LIST("List"),
    LARGE_ICONS("Large icons"),
    THUMBNAILS("Thumbnails");

    companion object {
        fun fromStored(value: String?): ExplorerViewMode =
            entries.firstOrNull { it.name == value } ?: LIST
    }
}
