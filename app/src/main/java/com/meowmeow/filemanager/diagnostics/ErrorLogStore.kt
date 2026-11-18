package com.meowmeow.filemanager.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.text.DateFormat
import java.util.Date

enum class ErrorArea {
    SCOPED_FOLDER_PICKER, SCOPED_BROWSING, SCOPED_FILE_OPERATION,
    DEVICE_BROWSING, DEVICE_FILE_OPERATION, CLEANUP_SCAN, CLEANUP_DELETE,
    STORAGE_OVERVIEW, EXTERNAL_FILE_OPEN, APP_CRASH, DIAGNOSTICS_TEST
}
data class ErrorEntry(
    val timestampMillis: Long,
    val area: ErrorArea,
    val category: String,
    val fatal: Boolean
)

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
        appendLine(item.category)
    }
}

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

    @Synchronized fun record(area: ErrorArea, error: Throwable, fatal: Boolean = false) {
        if (!_enabled.value) return
        addEntry(ErrorEntry(System.currentTimeMillis(), area, errorCategory(error), fatal), fatal)
    }

    @Synchronized fun recordTest() {
        if (_enabled.value) {
            addEntry(ErrorEntry(System.currentTimeMillis(), ErrorArea.DIAGNOSTICS_TEST, "Test entry", false), false)
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
                list += ErrorEntry(item.optLong("time"), area, category, item.optBoolean("fatal"))
            }
            list.takeLast(100)
        } catch (_: Exception) { emptyList() }
    }
}
