package com.meowmeow.filemanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.AnalysisKind
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.SearchScope
import com.meowmeow.filemanager.model.humanSize

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FileAnalysisScreen(
    state: FileAnalysisUiState,
    usingDevice: Boolean,
    sourcePath: List<String>,
    onBack: () -> Unit,
    onStart: (AnalysisKind, SearchScope, Int) -> Unit,
    onCancel: () -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    var mode by remember { mutableStateOf(AnalysisKind.LARGE) }
    var scope by remember { mutableStateOf(SearchScope.CURRENT_FOLDER) }
    var threshold by remember { mutableStateOf(50) }
    var locationMenu by remember { mutableStateOf(false) }
    var thresholdMenu by remember { mutableStateOf(false) }
    val rootLabel = if (usingDevice) "Device storage root + subfolders"
        else "Selected folder root + subfolders"
    fun label(s: SearchScope): String = when (s) {
        SearchScope.CURRENT_FOLDER -> "Current folder only"
        SearchScope.CURRENT_AND_SUBFOLDERS -> "Current folder + subfolders"
        SearchScope.ROOT_AND_SUBFOLDERS -> rootLabel
    }

    val showingCurrent = state.complete && state.kind == mode &&
        state.scope == scope && (mode != AnalysisKind.LARGE || state.thresholdMiB == threshold)

    BackHandler(onBack = onBack)
    // Switching to another tab also cancels a live scan.
    DisposableEffect(Unit) {
        onDispose { onCancel() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Find files") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back to Cleanup")
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Storage analysis", style = MaterialTheme.typography.titleMedium)
            Text("Read-only scans. Nothing is deleted or changed automatically.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = mode == AnalysisKind.LARGE, enabled = !state.isRunning,
                    onClick = { mode = AnalysisKind.LARGE },
                    label = { Text("Large files") }
                )
                FilterChip(
                    selected = mode == AnalysisKind.DUPLICATES, enabled = !state.isRunning,
                    onClick = { mode = AnalysisKind.DUPLICATES },
                    label = { Text("Exact duplicates") }
                )
            }
            Text("Scan location", style = MaterialTheme.typography.labelMedium)
            Box {
                AssistChip(
                    enabled = !state.isRunning, onClick = { locationMenu = true },
                    label = { Text(label(scope)) }
                )
                DropdownMenu(expanded = locationMenu,
                    onDismissRequest = { locationMenu = false }) {
                    SearchScope.entries.forEach { available ->
                        DropdownMenuItem(text = { Text(label(available)) },
                            onClick = { scope = available; locationMenu = false })
                    }
                }
            }
            Text(
                if (scope == SearchScope.ROOT_AND_SUBFOLDERS)
                    "Starts at the permitted storage root."
                else "Current folder: " +
                    (sourcePath.joinToString(" / ").ifBlank { "Open a folder in Files first" }) +
                    if (scope.recursive) " (including accessible subfolders)" else " (no subfolders)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            if (mode == AnalysisKind.LARGE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Minimum file size: ", style = MaterialTheme.typography.bodyMedium)
                    Box {
                        AssistChip(
                            enabled = !state.isRunning, onClick = { thresholdMenu = true },
                            label = { Text("$threshold MiB") }
                        )
                        DropdownMenu(expanded = thresholdMenu,
                            onDismissRequest = { thresholdMenu = false }) {
                            listOf(10, 50, 100, 500, 1024).forEach { amount ->
                                DropdownMenuItem(
                                    text = { Text("$amount MiB") },
                                    onClick = { threshold = amount; thresholdMenu = false }
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "Files are grouped by size and compared using SHA-256. " +
                        "Matching names alone do not count as duplicates.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !state.isRunning && sourcePath.isNotEmpty(),
                    onClick = { onStart(mode, scope, threshold) }
                ) { Text(if (showingCurrent) "Scan again" else "Scan files") }
                if (state.isRunning) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel scan") }
                }
            }
            if (state.isRunning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Scanning: ${state.itemsVisited} items / ${state.foldersVisited} folders" +
                        if (state.kind == AnalysisKind.DUPLICATES)
                            " · ${state.hashedFiles} files hashed" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (showingCurrent) {
                if (state.truncated || state.unreadable > 0) {
                    Text(
                        buildString {
                            if (state.truncated) append(
                                "Scan limit reached: results may be incomplete. "
                            )
                            if (state.unreadable > 0) append(
                                "${state.unreadable} items/folders were unreadable or changed."
                            )
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (state.kind == AnalysisKind.LARGE)
                        "${state.large.size} large files found · " +
                            humanSize(state.large.sumOf { it.entry.sizeBytes }) +
                            " among displayed files"
                    else "${state.duplicateGroups.size} duplicate groups · " +
                        humanSize(state.reclaimableBytes) + " potential savings",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    if (state.kind == AnalysisKind.DUPLICATES)
                        "Potential savings assume keeping one copy per group. " +
                            "Review each file before deleting anything."
                    else "Tap a result to open it. To remove files, review them in Files.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                if (showingCurrent && state.kind == AnalysisKind.LARGE) {
                    items(state.large, key = { it.entry.id }) { hit ->
                        AnalysisFileRow(hit, onOpenFile)
                    }
                    if (state.large.isEmpty()) item {
                        Text("No large files found in the scanned scope.",
                            modifier = Modifier.padding(16.dp))
                    }
                } else if (showingCurrent && state.kind == AnalysisKind.DUPLICATES) {
                    items(state.duplicateGroups) { group ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${group.members.size} identical files · " +
                                    humanSize(group.sizeBytes) + " each · " +
                                    humanSize(group.reclaimableBytes) + " potentially reclaimable",
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.titleSmall
                            )
                            group.members.forEach { hit ->
                                AnalysisFileRow(hit, onOpenFile)
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.duplicateGroups.isEmpty()) item {
                        Text("No exact duplicates found in the scanned scope.",
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisFileRow(
    hit: FileAnalysisHit,
    onOpenFile: (FileEntry) -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable { onOpenFile(hit.entry) },
        leadingContent = {
            Icon(Icons.Default.InsertDriveFile, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = {
            Text(hit.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(humanSize(hit.entry.sizeBytes) + " · " + hit.location,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    )
}
