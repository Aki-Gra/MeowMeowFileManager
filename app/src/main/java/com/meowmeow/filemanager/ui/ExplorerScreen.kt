package com.meowmeow.filemanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.BulkAction
import com.meowmeow.filemanager.model.CopyConflictChoice
import com.meowmeow.filemanager.model.FileKind
import com.meowmeow.filemanager.model.SearchScope
import com.meowmeow.filemanager.model.detectFileKind
import com.meowmeow.filemanager.model.SortMode
import com.meowmeow.filemanager.model.isValidFileName
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    state: ExplorerUiState,
    onChooseFolder: () -> Unit,
    onOpenDirectory: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onSort: (SortMode) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onCopy: (FileEntry) -> Unit,
    copyState: CopyUiState,
    onPaste: () -> Unit,
    onCopyCancel: () -> Unit,
    onDismissCopyNotice: () -> Unit,
    onCopyConflictDecision: (CopyConflictChoice) -> Unit,
    bulkState: BulkUiState,
    onBulkConflictDecision: (CopyConflictChoice) -> Unit,
    onBulkStart: () -> Unit,
    onBulkToggle: (FileEntry) -> Unit,
    onBulkSelectAll: (List<FileEntry>) -> Unit,
    onBulkClear: () -> Unit,
    onBulkLeave: () -> Unit,
    onBulkAction: (BulkAction) -> Unit,
    onBulkPaste: () -> Unit,
    onBulkConfirm: () -> Unit,
    onBulkBack: () -> Unit,
    onBulkCancel: () -> Unit,
    onBulkDiscard: () -> Unit,
    onDismissBulkNotice: () -> Unit,
    onDismissError: () -> Unit,
    searchState: FileSearchState,
    onSearch: (String, FileKind, SearchScope) -> Unit,
    onCancelSearch: () -> Unit,
    onOpenSearchFolder: (FileSearchHit) -> Unit,
    supportsFullAccess: Boolean,
    hasFullAccess: Boolean,
    usingDevice: Boolean,
    onRequestFullAccess: () -> Unit,
    onOpenDeviceStorage: () -> Unit,
    onBrowseDownloads: () -> Unit
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchKind by rememberSaveable { mutableStateOf(FileKind.ALL) }
    var searchScope by rememberSaveable { mutableStateOf(SearchScope.CURRENT_FOLDER) }
    var scopeMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Leaving Files (for Storage, Cleanup or Settings) must also stop scans.
    DisposableEffect(Unit) {
        onDispose {
            onCancelSearch()
            onBulkLeave()
        }
    }
    // A selection belongs to exactly one source folder.
    LaunchedEffect(state.path, usingDevice) {
        onBulkLeave()
    }

    BackHandler(enabled = searchVisible || bulkState.stage == BulkStage.SELECTING ||
        bulkState.stage == BulkStage.DESTINATION || state.path.size > 1, onBack = {
        when {
            searchVisible -> {
                searchVisible = false
                onCancelSearch()
            }
            bulkState.stage == BulkStage.SELECTING -> onBulkDiscard()
            bulkState.stage == BulkStage.DESTINATION && state.path.size <= 1 -> onBulkDiscard()
            else -> onNavigateUp()
        }
    })
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            onDismissError()
        }
    }
    LaunchedEffect(copyState.notice) {
        copyState.notice?.let {
            snackbar.showSnackbar(it)
            onDismissCopyNotice()
        }
    }
    LaunchedEffect(bulkState.notice) {
        bulkState.notice?.let {
            snackbar.showSnackbar(it)
            onDismissBulkNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (bulkState.stage != BulkStage.OFF) {
                BulkBottomBar(
                    state = bulkState,
                    canPaste = state.hasRoot && state.canCreateFolder && !state.isLoading,
                    onAction = onBulkAction,
                    onPasteReview = onBulkPaste,
                    onDiscard = onBulkDiscard,
                    onCancel = onBulkCancel
                )
            } else if (copyState.sourceName != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Copy: " + copyState.sourceName,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        if (copyState.isCopying) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Copied " + copyState.filesCopied + " file(s) · " +
                                copyState.foldersCopied + " folder(s)",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Navigate to the destination, then tap Paste here.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = !copyState.isCopying && state.hasRoot && state.canCreateFolder,
                                onClick = onPaste
                            ) { Text("Paste here") }
                            TextButton(onClick = onCopyCancel) {
                                Text(if (copyState.isCopying) "Cancel copy" else "Discard")
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MeowMeow 🐾", maxLines = 1)
                        if (state.path.isNotEmpty()) {
                            Text(
                                state.path.joinToString(" / "),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.path.size > 1) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                        }
                    }
                },
                actions = {
                    if (state.hasRoot && bulkState.stage == BulkStage.OFF) {
                        IconButton(onClick = {
                            searchVisible = !searchVisible
                            onCancelSearch()
                        }) {
                            Icon(if (searchVisible) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (searchVisible) "Close search" else "Search files")
                        }
                    }
                    if (state.hasRoot && !searchVisible &&
                        (bulkState.stage == BulkStage.OFF || bulkState.stage == BulkStage.SELECTING)) {
                        IconButton(
                            onClick = {
                                if (bulkState.stage == BulkStage.OFF) onBulkStart()
                                else onBulkDiscard()
                            }
                        ) {
                            Icon(Icons.Default.Checklist,
                                contentDescription = if (bulkState.stage == BulkStage.SELECTING)
                                    "Exit multi-select" else "Select multiple files")
                        }
                    }
                    if (state.hasRoot || supportsFullAccess) {
                        if (state.hasRoot) {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh files")
                            }
                        }
                        Box {
                            IconButton(onClick = { optionsMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Folder options")
                            }
                            DropdownMenu(expanded = optionsMenu, onDismissRequest = { optionsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Choose another folder") },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                                    onClick = { optionsMenu = false; onChooseFolder() }
                                )
                                if (supportsFullAccess) {
                                    DropdownMenuItem(
                                        text = { Text(if (hasFullAccess) "Device storage" else "Enable full file access") },
                                        onClick = {
                                            optionsMenu = false
                                            if (hasFullAccess) onOpenDeviceStorage() else onRequestFullAccess()
                                        }
                                    )
                                    if (hasFullAccess) {
                                        DropdownMenuItem(
                                            text = { Text("Downloads") },
                                            onClick = { optionsMenu = false; onBrowseDownloads() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.hasRoot && state.canCreateFolder && !state.isLoading) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                }
            }
        }
    ) { innerPadding ->
        when {
            searchVisible && state.hasRoot -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it; onCancelSearch() },
                        label = { Text("Name contains (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box {
                            AssistChip(
                                onClick = { typeMenu = true },
                                label = { Text(searchKind.label) }
                            )
                            DropdownMenu(
                                expanded = typeMenu,
                                onDismissRequest = { typeMenu = false }
                            ) {
                                FileKind.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.label) },
                                        onClick = {
                                            searchKind = type
                                            typeMenu = false
                                            onCancelSearch()
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Text("Search location", style = MaterialTheme.typography.labelMedium)
                    Box {
                        AssistChip(
                            onClick = { scopeMenu = true },
                            label = {
                                Text(
                                    when (searchScope) {
                                        SearchScope.CURRENT_FOLDER -> "Current folder only"
                                        SearchScope.CURRENT_AND_SUBFOLDERS -> "Current folder + subfolders"
                                        SearchScope.ROOT_AND_SUBFOLDERS ->
                                            if (usingDevice) "Device storage root + subfolders"
                                            else "Selected folder root + subfolders"
                                    }
                                )
                            }
                        )
                        DropdownMenu(
                            expanded = scopeMenu,
                            onDismissRequest = { scopeMenu = false }
                        ) {
                            SearchScope.entries.forEach { scope ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (scope) {
                                                SearchScope.CURRENT_FOLDER -> "Current folder only"
                                                SearchScope.CURRENT_AND_SUBFOLDERS ->
                                                    "Current folder + subfolders"
                                                SearchScope.ROOT_AND_SUBFOLDERS ->
                                                    if (usingDevice) "Device storage root + subfolders"
                                                    else "Selected folder root + subfolders"
                                            }
                                        )
                                    },
                                    onClick = {
                                        scopeMenu = false
                                        searchScope = scope
                                        onCancelSearch()
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        when (searchScope) {
                            SearchScope.CURRENT_FOLDER ->
                                "Search just the folder you're viewing."
                            SearchScope.CURRENT_AND_SUBFOLDERS ->
                                "Search this folder and all accessible folders inside it."
                            SearchScope.ROOT_AND_SUBFOLDERS ->
                                if (usingDevice)
                                    "Search from the device storage root, including accessible subfolders."
                                else "Search from the folder you selected in Android's picker, including its subfolders."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            enabled = !searchState.isRunning,
                            onClick = { onSearch(searchQuery, searchKind, searchScope) }
                        ) { Text("Find files") }
                        if (searchState.isRunning) {
                            TextButton(onClick = onCancelSearch) { Text("Cancel scan") }
                        }
                    }
                    if (searchState.isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (searchState.isRunning || searchState.finished) {
                        Text(
                            if (searchState.isRunning)
                                "Scanning: ${searchState.entriesVisited} items / ${searchState.foldersVisited} folders"
                            else "${searchState.hits.size} matches in ${searchState.entriesVisited} items",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (searchState.truncated || searchState.unreadableFolders > 0) {
                        Text(
                            buildString {
                                if (searchState.truncated) append("Result/scan limit reached. Results are incomplete. ")
                                if (searchState.unreadableFolders > 0)
                                    append("${searchState.unreadableFolders} folders were unreadable.")
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    searchState.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (searchState.finished && searchState.hits.isEmpty()) {
                        Text(
                            if (searchState.truncated || searchState.unreadableFolders > 0)
                                "No matches in the folders scanned."
                            else "No matching files found.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(searchState.hits, key = { it.entry.id }) { hit ->
                            ListItem(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (hit.entry.isDirectory) {
                                        onOpenSearchFolder(hit)
                                        searchVisible = false
                                    } else onOpenFile(hit.entry)
                                },
                                leadingContent = {
                                    Icon(
                                        if (hit.entry.isDirectory) Icons.Default.Folder
                                        else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                headlineContent = {
                                    Text(hit.entry.name, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        if (hit.entry.isDirectory) "Folder · ${hit.location}"
                                        else "${detectFileKind(hit.entry).label} · ${formatSize(hit.entry.sizeBytes)} · ${hit.location}",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
            state.isLoading && state.entries.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            !state.hasRoot -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(88.dp)
                    )
                    Spacer(Modifier.size(20.dp))
                    Text("Your files, your paws", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Choose a folder to explore, or enable full file access to browse Downloads and shared storage.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.size(20.dp))
                    Button(onClick = onChooseFolder) { Text("Choose a folder") }
                    if (supportsFullAccess) {
                        Spacer(Modifier.size(12.dp))
                        Button(onClick = if (hasFullAccess) onOpenDeviceStorage else onRequestFullAccess) {
                            Text(if (hasFullAccess) "Browse device storage" else "Enable full file access")
                        }
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    if (bulkState.stage == BulkStage.SELECTING) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(bulkState.count.toString() + " selected",
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { onBulkSelectAll(state.entries) }) {
                                Text("Select all")
                            }
                            TextButton(onClick = onBulkClear) { Text("Deselect all") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${state.entries.size} items",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Box {
                            AssistChip(
                                onClick = { sortMenu = true },
                                label = { Text(state.sortMode.label) },
                                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) }
                            )
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sort.label) },
                                        onClick = { sortMenu = false; onSort(sort) }
                                    )
                                }
                            }
                        }
                    }
                    if (state.entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Nothing in this folder yet. 🐱")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 100.dp)
                        ) {
                            items(state.entries, key = { it.id }) { entry ->
                                FileRow(
                                    entry = entry,
                                    onOpen = { if (entry.isDirectory) onOpenDirectory(entry) else onOpenFile(entry) },
                                    onRename = { renameTarget = entry },
                                    onDelete = { deleteTarget = entry },
                                    onCopy = { onCopy(entry) },
                                    selectionMode = bulkState.stage == BulkStage.SELECTING,
                                    selected = entry.id in bulkState.selectedIds,
                                    actionsEnabled = bulkState.stage == BulkStage.OFF,
                                    onSelect = { onBulkToggle(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BulkReviewDialog(
        state = bulkState,
        onConfirm = onBulkConfirm,
        onCancel = onBulkBack
    )
    CopyConflictDialog(
        conflict = copyState.conflict,
        onDecision = onCopyConflictDecision
    )
    CopyConflictDialog(
        conflict = bulkState.conflict,
        onDecision = onBulkConflictDecision
    )

    if (creating) {
        NameDialog(
            title = "New folder",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; onCreateFolder(name) }
        )
    }
    renameTarget?.let { entry ->
        NameDialog(
            title = "Rename ${entry.name}",
            initialName = entry.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name -> renameTarget = null; onRename(entry, name) }
        )
    }
    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${entry.name}?") },
            text = { Text("This permanently deletes the item. It may not be recoverable.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(entry) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    actionsEnabled: Boolean,
    onSelect: () -> Unit
) {
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(
            onClick = if (selectionMode) onSelect else onOpen
        ),
        leadingContent = {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onSelect() })
            } else Icon(
                imageVector = when {
                    entry.isDirectory -> Icons.Default.Folder
                    entry.mimeType?.startsWith("image/") == true -> Icons.Default.Image
                    entry.mimeType?.startsWith("video/") == true -> Icons.Default.Movie
                    entry.mimeType?.startsWith("audio/") == true -> Icons.Default.AudioFile
                    entry.mimeType == "application/pdf" -> Icons.Default.Description
                    else -> Icons.Default.InsertDriveFile
                },
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        },
        headlineContent = {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                if (entry.isDirectory) "Folder" else buildString {
                    append(formatSize(entry.sizeBytes))
                    if (entry.modifiedAtMillis > 0L) {
                        append(" • ")
                        append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.modifiedAtMillis)))
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            if (actionsEnabled) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${entry.name}")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = { menuExpanded = false; onCopy() }
                        )
                        if (entry.canWrite) DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onRename() }
                        )
                        if (entry.canWrite) DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(initialName, title) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Name") },
                isError = value.isNotEmpty() && !isValidFileName(value)
            )
        },
        confirmButton = {
            TextButton(enabled = isValidFileName(value), onClick = { onConfirm(value) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatSize(size: Long): String {
    if (size < 1024L) return "$size B"
    var value = size.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < 4) { value /= 1024.0; unit++ }
    return String.format(Locale.getDefault(), "%.1f %s", value, listOf("B", "KB", "MB", "GB", "TB")[unit])
}
