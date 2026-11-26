package com.meowmeow.filemanager.model

/**
 * Conservative file-operation overlap detection.
 *
 * Plain filesystem paths have component boundaries. SAF document IDs are opaque
 * provider data, not filesystem paths, so a shared provider/tree (or uncertain
 * relationship) must be treated as a potential conflict.
 */
sealed interface TaskScope {
    data class Device(val absolutePath: String) : TaskScope
    data class Saf(val authority: String, val treeId: String) : TaskScope
    data object Unknown : TaskScope
}

private fun normalPath(input: String): String {
    val normalized = input.replace('\\', '/').trimEnd('/')
    return if (normalized.isBlank()) "/" else normalized
}

fun scopesMayOverlap(first: TaskScope, second: TaskScope): Boolean = when {
    first is TaskScope.Device && second is TaskScope.Device -> {
        val a = normalPath(first.absolutePath)
        val b = normalPath(second.absolutePath)
        a == b || a == "/" || b == "/" || a.startsWith("$b/") || b.startsWith("$a/")
    }
    first is TaskScope.Saf && second is TaskScope.Saf -> {
        // Different providers cannot be proven to refer to disjoint storage.
        // Even different trees from one provider can overlap or alias.
        true
    }
    else -> true // A device path could also be reachable through SAF.
}
