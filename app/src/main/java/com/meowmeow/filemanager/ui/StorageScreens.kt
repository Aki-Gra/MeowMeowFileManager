package com.meowmeow.filemanager.ui

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.MeowMeowApp
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.model.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SpaceInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val filesystemFallback: Boolean
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)
    val usedRatio: Float get() =
        if (totalBytes <= 0) 0f else (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/**
 * User-facing device capacity, not a measure of files accessible to MeowMeow.
 *
 * StorageStatsManager values are intended for visual display: total reflects the
 * advertised primary internal storage size, while free includes reclaimable
 * caches. StatFs only measures the shared-storage filesystem and can therefore
 * under-report both values relative to Android Settings.
 */
private fun readSpaceInfo(context: Context): SpaceInfo {
    val deviceStats = runCatching {
        val manager = requireNotNull(context.getSystemService(StorageStatsManager::class.java))
        val uuid = StorageManager.UUID_DEFAULT
        val total = manager.getTotalBytes(uuid)
        val free = manager.getFreeBytes(uuid)
        check(total > 0L && free >= 0L) { "Invalid device storage stats." }
        SpaceInfo(total, free.coerceAtMost(total), filesystemFallback = false)
    }.getOrNull()
    if (deviceStats != null) return deviceStats

    // Explain fallback scope in the UI rather than calling these device totals.
    val filesystem = StatFs(Environment.getExternalStorageDirectory().path)
    val total = filesystem.totalBytes
    val free = filesystem.availableBytes.coerceIn(0L, total.coerceAtLeast(0L))
    return SpaceInfo(total, free, filesystemFallback = true)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StorageOverviewScreen(
    hasFullAccess: Boolean,
    supportsFullAccess: Boolean,
    onGrantAccess: () -> Unit,
    onBrowseDownloads: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenSystemStorage: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    var refresh by remember { mutableIntStateOf(0) }
    val stats by produceState<SpaceInfo?>(initialValue = null, key1 = refresh) {
        // If the user switches tabs during refresh, cancellation is normal and
        // must not become a false error-log entry.
        val result = resultUnlessCancelled {
            withContext(Dispatchers.IO) { readSpaceInfo(context) }
        }
        result.onFailure {
            (context as MeowMeowApp).errorLog.record(ErrorArea.STORAGE_OVERVIEW, it, operation = ErrorOperation.STORAGE_READ)
        }
        value = result.getOrNull()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Storage overview") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Storage, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (stats?.filesystemFallback == true) "Shared storage filesystem"
                        else "Internal device storage",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (stats == null) {
                        Text("Storage information unavailable.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val info = requireNotNull(stats)
                        Text(
                            Formatter.formatFileSize(context, info.usedBytes) + " used of " +
                                Formatter.formatFileSize(context, info.totalBytes)
                        )
                        LinearProgressIndicator(
                            progress = { info.usedRatio },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            Formatter.formatFileSize(context, info.availableBytes) + " free",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        if (stats?.filesystemFallback == true)
                            "Filesystem-only figures (fallback). They may differ from Android Settings " +
                                "because they exclude system-reserved capacity and reclaimable cache."
                        else
                            "Device totals include system and other app data; free space includes " +
                                "cache Android may reclaim. These figures do not indicate what " +
                                "MeowMeow can safely delete.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { refresh++ }) { Text("Refresh space") }
                }
            }
            Button(onClick = onOpenCleanup, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Review cleanup suggestions")
            }
            if (hasFullAccess) {
                OutlinedButton(onClick = onBrowseDownloads, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Browse Downloads")
                }
            } else if (supportsFullAccess) {
                OutlinedButton(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                    Text("Enable full file-manager access")
                }
            }
            TextButton(onClick = onOpenSystemStorage) {
                Text("Open Android storage settings")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(
    state: CleanupUiState,
    hasFullAccess: Boolean,
    supportsFullAccess: Boolean,
    onGrantAccess: () -> Unit,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onDelete: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onDismissMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Cleanup") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Large files in Downloads", style = MaterialTheme.typography.titleMedium)
            Text("Review downloads of 50 MiB or more. Nothing is removed automatically. " +
                "Deleting selected files is permanent and cannot be undone.",
                style = MaterialTheme.typography.bodyMedium)
            if (!hasFullAccess) {
                Text(
                    if (supportsFullAccess)
                        "Enable All files access to scan Downloads."
                    else
                        "Full file-manager access requires Android 11 or newer. You can still use the folder picker.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (supportsFullAccess) {
                    Button(onClick = onGrantAccess) { Text("Grant file access") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(enabled = !state.isLoading, onClick = onScan) {
                        Text(if (state.scanned) "Scan again" else "Scan Downloads")
                    }
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp))
                }
                if (state.scanned) {
                    Text(state.candidates.size.toString() + " large file(s) found",
                        style = MaterialTheme.typography.labelLarge)
                    if (state.truncated) {
                        Text("Scan reached 10,000 entries. Results may be incomplete.",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.selected.isNotEmpty()) {
                    Button(onClick = { confirming = true }, enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Delete " + state.selected.size + " selected (" +
                            humanSize(state.selectedBytes) + ")")
                    }
                }
                if (state.scanned && state.candidates.isEmpty() && !state.isLoading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("No large downloads found. 🐱")
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(state.candidates, key = { it.id }) { file ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !state.isLoading) {
                                onToggle(file.id)
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = file.id in state.selectedIds,
                                    enabled = !state.isLoading,
                                    onCheckedChange = { onToggle(file.id) }
                                )
                            },
                            headlineContent = { Text(file.path, maxLines = 2) },
                            supportingContent = { Text(humanSize(file.sizeBytes)) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Permanently delete selected files?") },
            text = { Text("Delete " + state.selected.size + " files (" +
                humanSize(state.selectedBytes) + ") from Downloads? " +
                "This bypasses the trash and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}
