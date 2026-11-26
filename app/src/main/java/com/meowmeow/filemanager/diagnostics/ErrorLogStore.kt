package com.meowmeow.filemanager.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import com.meowmeow.filemanager.model.shouldRecordOperationError
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ErrorArea {
    SCOPED_FOLDER_PICKER, SCOPED_BROWSING, SCOPED_FILE_OPERATION,
    DEVICE_BROWSING, DEVICE_FILE_OPERATION, CLEANUP_SCAN, CLEANUP_DELETE,
    STORAGE_OVERVIEW, EXTERNAL_FILE_OPEN, APP_CRASH, DIAGNOSTICS_TEST,
    LOG_EXPORT, COPY_OPERATION, FILE_ANALYSIS, DOCUMENT_READER, FILE_SHARING
}
/**
 * Stable, non-sensitive action identifiers. Do not reuse or rename shipped codes:
 * testers can search for them in future versions and in exported .txt logs.
 */
enum class ErrorOperation(val code: String, val title: String) {
    UNKNOWN("OLD", "Unspecified (older entry)"),
    SAF_RESTORE("SAF-RST", "Restore chosen folder"),
    SAF_PICK("SAF-PICK", "Choose folder"),
    SAF_LIST("SAF-LIST", "List chosen folder"),
    SAF_CREATE("SAF-MKDIR", "Create folder (SAF)"),
    SAF_RENAME("SAF-REN", "Rename item (SAF)"),
    SAF_DELETE("SAF-DEL", "Delete item (SAF)"),
    FS_COPY("FS-COPY", "Copy device files"),
    SAF_COPY("SAF-COPY", "Copy files in chosen folder"),
    COPY_BETWEEN("COPY-MIX", "Copy between storage modes"),
    BULK_COPY("BULK-COPY", "Bulk copy files"),
    BULK_MOVE("BULK-MOVE", "Bulk move files"),
    BULK_DELETE("BULK-DEL", "Bulk delete files"),
    FS_OPEN_FOLDER("FS-OPEN", "Open folder"),
    FS_LIST("FS-LIST", "List device folder"),
    FS_CREATE("FS-MKDIR", "Create device folder"),
    FS_RENAME("FS-REN", "Rename device item"),
    FS_DELETE("FS-DEL", "Delete device item"),
    FS_SEARCH("FS-SEARCH", "Search device storage"),
    DUPLICATE_SCAN("DUP-SCAN", "Find exact duplicates"),
    DUPLICATE_DELETE("DUP-DEL", "Delete verified duplicate files"),
    LARGE_FILE_SCAN("LARGE-SCAN", "Scan for large files"),
    SAF_SEARCH("SAF-SEARCH", "Search chosen folder"),
    CLEANUP_SCAN("CLN-SCAN", "Scan large downloads"),
    CLEANUP_DELETE("CLN-DEL", "Delete selected download"),
    STORAGE_READ("STO-READ", "Read storage information"),
    EXTERNAL_OPEN("VIEW-OPEN", "Open in another app"),
    FILE_SHARE("SHARE-FILE", "Share a file"),
    LOG_EXPORT("LOG-EXPORT", "Export error log"),
    APP_CRASH("APP-CRASH", "Unhandled app crash"),
    READER_OPEN("READ-OPEN", "Open in-app document"),
    READER_RENDER("READ-PDF", "Render PDF page"),
    READER_IMAGE("READ-IMG", "Preview image"),
    DIAGNOSTICS_TEST("LOG-TEST", "Test log")
}

data class ErrorEntry(
    val timestampMillis: Long,
    val area: ErrorArea,
    val category: String,
    val fatal: Boolean,
    val operation: ErrorOperation = ErrorOperation.UNKNOWN
)

/** Fixed codes combine the action with a safe broad reason, never exception details. */
fun errorCode(entry: ErrorEntry): String {
    val reason = when (entry.category) {
        "Permission denied" -> "01"
        "Storage I/O error" -> "02"
        "Invalid operation" -> "03"
        "Operation unavailable" -> "04"
        "Test entry" -> "00"
        else -> "99"
    }
    return "MM-" + entry.operation.code + "-" + reason
}

/** Never capture raw exception messages or stack traces: they can contain private paths. */
fun errorCategory(error: Throwable): String = when (error) {
    is SecurityException -> "Permission denied"
    is IOException -> "Storage I/O error"
    is IllegalArgumentException -> "Invalid operation"
    is IllegalStateException -> "Operation unavailable"
    else -> "Unexpected failure"
}

fun formatErrorLog(events: List<ErrorEntry>): String = buildString {
    appendLine("MeowMeow local error log")
    appendLine("No filenames, paths, messages or stack traces are collected.")
    if (events.isEmpty()) appendLine("No errors recorded.")
    events.asReversed().forEach { item ->
        append(DateFormat.getDateTimeInstance().format(Date(item.timestampMillis)))
        append(" | ")
        append(if (item.area == ErrorArea.DIAGNOSTICS_TEST) "TEST" else if (item.fatal) "CRASH" else "ERROR")
        append(" | ")
        append(item.area.name.replace('_', ' '))
        append(" | ")
        append(errorCode(item))
        append(" | ")
        append(item.operation.title)
        append(" | ")
        appendLine(item.category)
    }
}

/** Suggested filename only. The user selects the destination through Android's Save dialog. */
fun suggestedErrorLogFileName(now: Date = Date()): String =
    "MeowMeow-error-log-" +
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(now) + ".txt"

/** Private app storage. Retains up to 100 entries; no network or automatic sharing. */
class ErrorLogStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("local_error_log", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled: StateFlow<Boolean> = _enabled
    private val _entries = MutableStateFlow(readSaved())
    val entries: StateFlow<List<ErrorEntry>> = _entries

    @Synchronized fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    @Synchronized fun record(
        area: ErrorArea,
        error: Throwable,
        fatal: Boolean = false,
        operation: ErrorOperation = ErrorOperation.UNKNOWN
    ) {
        if (!_enabled.value || !shouldRecordOperationError(error)) return
        addEntry(ErrorEntry(System.currentTimeMillis(), area, errorCategory(error), fatal, operation), fatal)
    }

    @Synchronized fun recordTest() {
        if (_enabled.value) {
            addEntry(ErrorEntry(System.currentTimeMillis(), ErrorArea.DIAGNOSTICS_TEST, "Test entry", false, ErrorOperation.DIAGNOSTICS_TEST), false)
        }
    }

    private fun addEntry(event: ErrorEntry, synchronous: Boolean) {
        val next = (_entries.value + event).takeLast(100)
        _entries.value = next
        val edit = prefs.edit().putString("entries", encode(next))
        if (synchronous) edit.commit() else edit.apply()
    }

    @Synchronized fun clear() {
        prefs.edit().remove("entries").commit()
        _entries.value = emptyList()
    }

    private fun encode(list: List<ErrorEntry>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().apply {
                put("time", it.timestampMillis)
                put("area", it.area.name)
                put("type", it.category)
                put("fatal", it.fatal)
                put("operation", it.operation.name)
            })
        }
    }.toString()

    private fun readSaved(): List<ErrorEntry> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            val list = mutableListOf<ErrorEntry>()
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val area = ErrorArea.entries.firstOrNull { it.name == item.optString("area") } ?: continue
                val category = item.optString("type")
                if (category !in setOf("Permission denied", "Storage I/O error",
                        "Invalid operation", "Operation unavailable", "Unexpected failure", "Test entry")) continue
                // Older saved entries do not have an operation field: keep them,
                // but clearly mark their source as unknown rather than guessing.
                val op = ErrorOperation.entries.firstOrNull {
                    it.name == item.optString("operation")
                } ?: ErrorOperation.UNKNOWN
                list += ErrorEntry(item.optLong("time"), area, category, item.optBoolean("fatal"), op)
            }
            list.takeLast(100)
        } catch (_: Exception) { emptyList() }
    }
}
