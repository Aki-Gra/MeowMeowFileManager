package com.meowmeow.filemanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.diagnostics.ErrorEntry
import com.meowmeow.filemanager.diagnostics.formatErrorLog
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    loggingEnabled: Boolean,
    events: List<ErrorEntry>,
    onSetLoggingEnabled: (Boolean) -> Unit,
    onRecordTest: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val report = remember(events) { formatErrorLog(events) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Error logging", style = MaterialTheme.typography.titleLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Save errors on this device",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Enabled by default. Turn off to stop recording new errors.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = loggingEnabled, onCheckedChange = onSetLoggingEnabled)
                    }
                    Text(
                        "Only timestamps, operation categories and broad error types are saved. " +
                            "No filenames, file paths, file contents, exception messages or stack traces. " +
                            "Nothing is uploaded automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Last " + events.size + " of up to 100 entries",
                        style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(enabled = loggingEnabled, onClick = onRecordTest) {
                        Text("Add test log entry")
                    }
                    OutlinedButton(
                        enabled = events.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onExport
                    ) { Text("Export logs to .txt") }
                    Text(
                        "Choose Downloads or another destination in Android's Save dialog. " +
                            "An export is never created or shared automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = events.isNotEmpty(), onClick = {
                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clip.setPrimaryClip(ClipData.newPlainText("MeowMeow error log", report))
                        }) { Text("Copy") }
                        OutlinedButton(enabled = events.isNotEmpty(), onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share error log"))
                        }) { Text("Share") }
                        TextButton(enabled = events.isNotEmpty(), onClick = {
                            confirmClear = true
                        }) { Text("Clear") }
                    }
                }
                HorizontalDivider()
            }
            if (events.isEmpty()) {
                item {
                    Column(Modifier.padding(20.dp)) {
                        Text("No errors recorded 🐱", style = MaterialTheme.typography.titleMedium)
                        Text("If an operation fails, its category will appear here.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(events.asReversed()) { event ->
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(if (event.area == com.meowmeow.filemanager.diagnostics.ErrorArea.DIAGNOSTICS_TEST)
                            "Test" else if (event.fatal) "Crash" else "Error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                        Text(event.area.name.replace('_', ' '),
                            style = MaterialTheme.typography.titleSmall)
                        Text(event.category, style = MaterialTheme.typography.bodyMedium)
                        Text(DateFormat.getDateTimeInstance().format(Date(event.timestampMillis)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear error log?") },
            text = { Text("Delete all saved errors on this device? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear log") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}
