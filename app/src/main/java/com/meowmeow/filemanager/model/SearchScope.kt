package com.meowmeow.filemanager.model

/**
 * Explicitly choose the starting folder AND whether to descend. Subfolder
 * searches must not silently switch from the current folder to storage root.
 */
enum class SearchScope {
    CURRENT_FOLDER,
    CURRENT_AND_SUBFOLDERS,
    ROOT_AND_SUBFOLDERS;

    val recursive: Boolean get() = this != CURRENT_FOLDER
}

/** Shared selection logic for primary-device and SAF-backed browsing. */
fun <T> searchStartFolder(root: T?, current: T?, scope: SearchScope): T? =
    when (scope) {
        SearchScope.CURRENT_FOLDER, SearchScope.CURRENT_AND_SUBFOLDERS -> current
        SearchScope.ROOT_AND_SUBFOLDERS -> root
    }
