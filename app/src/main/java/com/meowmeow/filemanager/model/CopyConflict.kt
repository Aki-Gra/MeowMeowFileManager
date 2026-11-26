package com.meowmeow.filemanager.model

/** This decision is requested only when an existing destination name conflicts. */
data class CopyConflict(
    val name: String,
    val suggestedName: String,
    val canReplace: Boolean
)

sealed interface CopyConflictChoice {
    data object Skip : CopyConflictChoice
    data object Replace : CopyConflictChoice
    data class Rename(val newName: String) : CopyConflictChoice
}

/** Keep the original extension when suggesting a distinct, user-editable name. */
fun suggestCopyName(name: String, exists: (String) -> Boolean): String {
    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < name.length - 1
    val stem = if (hasExtension) name.substring(0, dot) else name
    val ext = if (hasExtension) name.substring(dot) else ""
    for (i in 1..1000) {
        val suffix = if (i == 1) " (copy)" else " (copy $i)"
        val candidate = stem + suffix + ext
        if (isValidFileName(candidate) && !exists(candidate)) return candidate
    }
    throw ExpectedFileOperationException("Cannot suggest a free name here. Choose another folder.")
}
