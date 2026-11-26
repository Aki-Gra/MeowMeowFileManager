package com.meowmeow.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.io.IOException
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowmeow.filemanager.diagnostics.ErrorArea
import com.meowmeow.filemanager.diagnostics.ErrorOperation
import com.meowmeow.filemanager.diagnostics.formatErrorLog
import com.meowmeow.filemanager.diagnostics.suggestedErrorLogFileName
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.ui.TaskCenterViewModel
import com.meowmeow.filemanager.ui.TaskCenterOverlay
import com.meowmeow.filemanager.ui.BackgroundTaskKind
import com.meowmeow.filemanager.ui.copyTaskScope
import com.meowmeow.filemanager.ui.deviceTaskScope
import com.meowmeow.filemanager.ui.CleanupScreen
import com.meowmeow.filemanager.ui.CleanupViewModel
import com.meowmeow.filemanager.ui.FileAnalysisViewModel
import com.meowmeow.filemanager.ui.FileAnalysisScreen
import com.meowmeow.filemanager.ui.CopyViewModel
import com.meowmeow.filemanager.ui.BulkViewModel
import com.meowmeow.filemanager.ui.DeviceExplorerViewModel
import com.meowmeow.filemanager.ui.ExplorerScreen
import com.meowmeow.filemanager.ui.ExplorerViewModel
import com.meowmeow.filemanager.ui.StorageOverviewScreen
import com.meowmeow.filemanager.ui.SettingsScreen
import com.meowmeow.filemanager.ui.theme.MeowMeowTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page { FILES, STORAGE, CLEANUP, ANALYSIS, SETTINGS }

class MainActivity : ComponentActivity() {
    private var allFilesGranted by mutableStateOf(false)
    private val taskCenter by lazy {
        ViewModelProvider(this)[TaskCenterViewModel::class.java]
    }

    override fun onResume() {
        super.onResume()
        allFilesGranted = canBrowseSharedStorage()
        val allowedSafTrees = contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapNotNull { permission ->
                try {
                    permission.uri.authority?.let { authority ->
                        authority to DocumentsContract.getTreeDocumentId(permission.uri)
                    }
                } catch (_: Exception) { null }
            }.toSet()
        taskCenter.reconcileSafPermissions(allowedSafTrees)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorLog = (application as MeowMeowApp).errorLog
        val scoped = ViewModelProvider(
            this, ExplorerViewModel.Factory(applicationContext, taskCenter)
        )[ExplorerViewModel::class.java]
        val device = ViewModelProvider(
            this, DeviceExplorerViewModel.Factory(applicationContext, taskCenter)
        )[DeviceExplorerViewModel::class.java]
        val cleanup = ViewModelProvider(
            this, CleanupViewModel.Factory(applicationContext, taskCenter)
        )[CleanupViewModel::class.java]
        val analysis = ViewModelProvider(
            this, FileAnalysisViewModel.Factory(applicationContext, taskCenter)
        )[FileAnalysisViewModel::class.java]
        val copy = ViewModelProvider(
            this, CopyViewModel.Factory(applicationContext, taskCenter)
        )[CopyViewModel::class.java]
        val bulk = ViewModelProvider(
            this, BulkViewModel.Factory(applicationContext, taskCenter)
        )[BulkViewModel::class.java]
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        setContent {
            val scopedState by scoped.state.collectAsStateWithLifecycle()
            val deviceState by device.state.collectAsStateWithLifecycle()
            val deviceSearch by device.search.collectAsStateWithLifecycle()
            val scopedSearch by scoped.search.collectAsStateWithLifecycle()
            val cleanupState by cleanup.state.collectAsStateWithLifecycle()
            val analysisState by analysis.state.collectAsStateWithLifecycle()
            val taskState by taskCenter.state.collectAsStateWithLifecycle()
            val copyState by copy.state.collectAsStateWithLifecycle()
            val bulkState by bulk.state.collectAsStateWithLifecycle()
            val savedErrors by errorLog.entries.collectAsStateWithLifecycle()
            val errorLoggingEnabled by errorLog.enabled.collectAsStateWithLifecycle()
            var page by rememberSaveable { mutableStateOf(Page.FILES) }
            var usingDevice by rememberSaveable { mutableStateOf(canBrowseSharedStorage()) }
            var previousAllFilesAccess by remember { mutableStateOf(allFilesGranted) }
            LaunchedEffect(allFilesGranted) {
                if (previousAllFilesAccess && !allFilesGranted) taskCenter.accessRevoked()
                previousAllFilesAccess = allFilesGranted
            }
            // Save the exact private, sanitized report that existed when Export was tapped.
            // Preserve across Activity recreation while the system Save dialog is open.
            var pendingExportText by rememberSaveable { mutableStateOf<String?>(null) }
            val exportPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain")
            ) { uri ->
                val report = pendingExportText
                pendingExportText = null
                if (uri != null && report != null) {
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                                        writer.write(report)
                                    }
                                } ?: throw IOException("Selected document is not writable")
                            }
                            Toast.makeText(this@MainActivity, "Error log exported", Toast.LENGTH_SHORT).show()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            errorLog.record(ErrorArea.LOG_EXPORT, failure, operation = ErrorOperation.LOG_EXPORT)
                            Toast.makeText(this@MainActivity, "Could not save error log", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    usingDevice = false
                    page = Page.FILES
                    scoped.chooseTree(uri)
                }
            }

            LaunchedEffect(allFilesGranted) {
                if (allFilesGranted) {
                    device.openRoot()
                    usingDevice = true
                } else {
                    usingDevice = false
                }
            }
            LaunchedEffect(page, allFilesGranted) {
                if (page == Page.CLEANUP && allFilesGranted &&
                    !cleanupState.scanned && !cleanupState.isLoading) cleanup.scan()
            }

            val browseDevice = {
                if (allFilesGranted) {
                    usingDevice = true
                    device.openRoot()
                    page = Page.FILES
                } else if (taskCenter.allowMutation("Change storage permission", null)) {
                    requestAllFilesAccess()
                }
            }
            val browseDownloads = {
                if (allFilesGranted) {
                    usingDevice = true
                    device.openDownloads()
                    page = Page.FILES
                } else if (taskCenter.allowMutation("Change storage permission", null)) {
                    requestAllFilesAccess()
                }
            }

            MeowMeowTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                enabled = !analysisState.isDeleting,
                                selected = page == Page.FILES,
                                onClick = {
                                    if (page != Page.FILES && usingDevice && allFilesGranted) device.refresh()
                                    page = Page.FILES
                                },
                                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                label = { Text("Files") }
                            )
                            NavigationBarItem(
                                enabled = !analysisState.isDeleting,
                                selected = page == Page.STORAGE,
                                onClick = { page = Page.STORAGE },
                                icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                label = { Text("Storage") }
                            )
                            NavigationBarItem(
                                enabled = !analysisState.isDeleting,
                                selected = page == Page.CLEANUP || page == Page.ANALYSIS,
                                onClick = { page = Page.CLEANUP },
                                icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                                label = { Text("Cleanup") }
                            )
                            NavigationBarItem(
                                enabled = !analysisState.isDeleting,
                                selected = page == Page.SETTINGS,
                                onClick = { page = Page.SETTINGS },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }
                            )
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (page) {
                            Page.FILES -> ExplorerScreen(
                                state = if (usingDevice) deviceState else scopedState,
                                onChooseFolder = {
                                    if (taskCenter.allowMutation("Change folder access", null))
                                        folderPicker.launch(null)
                                },
                                onOpenDirectory = { entry ->
                                    if (usingDevice) device.openDirectory(entry) else scoped.openDirectory(entry)
                                },
                                onOpenFile = ::openFile,
                                onNavigateUp = {
                                    if (usingDevice) device.navigateUp() else scoped.navigateUp()
                                },
                                onRefresh = {
                                    if (usingDevice) device.refresh() else scoped.refresh()
                                },
                                onSort = { mode ->
                                    if (usingDevice) device.setSortMode(mode) else scoped.setSortMode(mode)
                                },
                                onCreateFolder = { name ->
                                    if (usingDevice) device.createFolder(name) else scoped.createFolder(name)
                                },
                                onRename = { entry, name ->
                                    if (usingDevice) device.rename(entry, name) else scoped.rename(entry, name)
                                },
                                onDelete = { entry ->
                                    if (usingDevice) device.delete(entry) else scoped.delete(entry)
                                },
                                onCopy = { entry ->
                                    copy.select(
                                        if (usingDevice) device.copySelection(entry)
                                        else scoped.copySelection(entry),
                                        entry.name
                                    )
                                },
                                copyState = copyState,
                                onPaste = {
                                    val destinationModeIsDevice = usingDevice
                                    val destination = if (destinationModeIsDevice)
                                        device.copyDestination() else scoped.copyDestination()
                                    val locations = listOf(copyTaskScope(destination))
                                    if (taskCenter.allowMutation("Copy files", locations)) {
                                        copy.paste(destination) {
                                            taskCenter.noteChanged(locations)
                                            if (destinationModeIsDevice) device.refresh()
                                            else scoped.refresh()
                                        }
                                    }
                                },
                                onCopyCancel = copy::cancelOrClear,
                                onDismissCopyNotice = copy::dismissNotice,
                                onCopyConflictDecision = copy::answerConflict,
                                bulkState = bulkState,
                                onBulkConflictDecision = bulk::answerConflict,
                                onBulkStart = {
                                    if (!copyState.isCopying) {
                                        copy.cancelOrClear()
                                        bulk.startSelection()
                                    }
                                },
                                onBulkToggle = { entry ->
                                    bulk.toggle(entry,
                                        if (usingDevice) device.copySelection(entry)
                                        else scoped.copySelection(entry)
                                    )
                                },
                                onBulkSelectAll = { entries ->
                                    bulk.selectAll(entries) { entry ->
                                        if (usingDevice) device.copySelection(entry)
                                        else scoped.copySelection(entry)
                                    }
                                },
                                onBulkClear = bulk::clearSelection,
                                onBulkLeave = bulk::leaveSelection,
                                onBulkAction = bulk::chooseAction,
                                onBulkPaste = {
                                    val destination = if (usingDevice)
                                        device.copyDestination() else scoped.copyDestination()
                                    val label = (if (usingDevice) deviceState.path
                                        else scopedState.path).joinToString(" / ")
                                    bulk.reviewDestination(destination, label)
                                },
                                onBulkConfirm = {
                                    val locations = bulk.mutationPlaces().map(::copyTaskScope)
                                    if (taskCenter.allowMutation("Bulk file operation", locations)) {
                                        bulk.confirm {
                                            taskCenter.noteChanged(locations)
                                            if (allFilesGranted) device.refresh()
                                            scoped.refresh()
                                        }
                                    }
                                },
                                onBulkBack = bulk::backToDestination,
                                onBulkCancel = bulk::cancelRunning,
                                onBulkDiscard = bulk::abandon,
                                onDismissBulkNotice = bulk::dismissNotice,
                                onDismissError = {
                                    if (usingDevice) device.clearError() else scoped.clearError()
                                },
                                searchState = if (usingDevice) deviceSearch else scopedSearch,
                                searchOutdated = taskState.latestOutdated(
                                    if (usingDevice) BackgroundTaskKind.DEVICE_SEARCH
                                    else BackgroundTaskKind.FOLDER_SEARCH
                                ),
                                onSearch = { query, kind, scope ->
                                    if (usingDevice) device.startSearch(query, kind, scope)
                                    else scoped.startSearch(query, kind, scope)
                                },
                                onCancelSearch = {
                                    // A tab or storage-mode switch must cancel whichever scan was active.
                                    device.cancelSearch()
                                    scoped.cancelSearch()
                                },
                                onOpenSearchFolder = { hit ->
                                    if (usingDevice) device.openSearchFolder(hit)
                                    else scoped.openSearchFolder(hit)
                                },
                                supportsFullAccess = supported,
                                hasFullAccess = allFilesGranted,
                                usingDevice = usingDevice,
                                onRequestFullAccess = {
                                    if (taskCenter.allowMutation("Change storage permission", null))
                                        requestAllFilesAccess()
                                },
                                onOpenDeviceStorage = browseDevice,
                                onBrowseDownloads = browseDownloads
                            )
                            Page.STORAGE -> StorageOverviewScreen(
                                hasFullAccess = allFilesGranted,
                                supportsFullAccess = supported,
                                onGrantAccess = {
                                    if (taskCenter.allowMutation("Change storage permission", null))
                                        requestAllFilesAccess()
                                },
                                onBrowseDownloads = browseDownloads,
                                onOpenCleanup = { page = Page.CLEANUP },
                                onOpenSystemStorage = {
                                    if (taskCenter.allowMutation("Change storage settings", null))
                                        openSystemStorage()
                                }
                            )
                            Page.SETTINGS -> SettingsScreen(
                                loggingEnabled = errorLoggingEnabled,
                                events = savedErrors,
                                onSetLoggingEnabled = errorLog::setEnabled,
                                onRecordTest = errorLog::recordTest,
                                onExport = {
                                    if (savedErrors.isNotEmpty()) {
                                        pendingExportText = formatErrorLog(savedErrors)
                                        exportPicker.launch(suggestedErrorLogFileName())
                                    }
                                },
                                onClear = errorLog::clear
                            )
                            Page.ANALYSIS -> FileAnalysisScreen(
                                state = analysisState,
                                usingDevice = usingDevice,
                                sourcePath = if (usingDevice) deviceState.path else scopedState.path,
                                onBack = { page = Page.CLEANUP },
                                onLeave = analysis::cancelDeleteConfirmation,
                                onStart = { kind, scope, minimumMiB ->
                                    val folder = if (usingDevice) device.analysisFolder(scope)
                                        else scoped.analysisFolder(scope)
                                    val path = if (usingDevice) deviceState.path else scopedState.path
                                    val origin = (if (scope == com.meowmeow.filemanager.model.SearchScope.ROOT_AND_SUBFOLDERS)
                                        path.take(1) else path).joinToString(" / ")
                                    analysis.scan(folder, kind, scope, minimumMiB, origin)
                                },
                                onCancel = analysis::cancel,
                                onOpenFile = ::openFile,
                                outdatedResults = taskState.latestOutdated(
                                    BackgroundTaskKind.DUPLICATE_SCAN,
                                    BackgroundTaskKind.LARGE_FILE_SCAN
                                ),
                                onToggleDuplicate = analysis::toggleDuplicate,
                                onSelectGroup = analysis::selectDuplicateGroup,
                                onRequestDelete = analysis::requestDelete,
                                onRequestSingleDelete = analysis::requestSingleDelete,
                                onConfirmDelete = {
                                    val locations = analysis.pendingDeleteScopes()
                                    if (taskCenter.allowMutation("Delete duplicate files", locations)) {
                                        analysis.confirmDelete()
                                        taskCenter.noteChanged(locations)
                                    } else {
                                        analysis.cancelDeleteConfirmation()
                                    }
                                },
                                onDismissDelete = analysis::cancelDeleteConfirmation,
                                onCancelDeletion = analysis::cancelDeletion
                            )
                            Page.CLEANUP -> CleanupScreen(
                                state = cleanupState,
                                onOpenAnalysis = { page = Page.ANALYSIS },
                                hasFullAccess = allFilesGranted,
                                supportsFullAccess = supported,
                                onGrantAccess = {
                                    if (taskCenter.allowMutation("Change storage permission", null))
                                        requestAllFilesAccess()
                                },
                                onScan = cleanup::scan,
                                onToggle = cleanup::toggleSelected,
                                onDelete = {
                                    val locations = listOf(
                                        deviceTaskScope(Environment.getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_DOWNLOADS
                                        ))
                                    )
                                    if (taskCenter.allowMutation("Delete Downloads files", locations)) {
                                        cleanup.deleteSelected()
                                        taskCenter.noteChanged(locations)
                                    }
                                },
                                onDismissMessage = cleanup::clearMessage
                            )
                        }
                        TaskCenterOverlay(
                            state = taskState,
                            onOpen = { kind ->
                                page = when (kind) {
                                    BackgroundTaskKind.DEVICE_SEARCH -> {
                                        usingDevice = true
                                        Page.FILES
                                    }
                                    BackgroundTaskKind.FOLDER_SEARCH -> {
                                        usingDevice = false
                                        Page.FILES
                                    }
                                    BackgroundTaskKind.DUPLICATE_SCAN,
                                    BackgroundTaskKind.LARGE_FILE_SCAN,
                                    BackgroundTaskKind.DUPLICATE_DELETE -> Page.ANALYSIS
                                    BackgroundTaskKind.FILE_COPY,
                                    BackgroundTaskKind.BULK_OPERATION,
                                    BackgroundTaskKind.FILE_MUTATION -> Page.FILES
                                    BackgroundTaskKind.DOWNLOADS_SCAN,
                                    BackgroundTaskKind.DOWNLOADS_DELETE -> Page.CLEANUP
                                }
                            },
                            onStop = taskCenter::stop,
                            onDismiss = taskCenter::dismiss,
                            onKeepConflict = taskCenter::keepCurrentTasks,
                            onStopConflict = taskCenter::stopConflictingTasks,
                            onDismissNotice = taskCenter::dismissNotice,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }
    }

    private fun canBrowseSharedStorage(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:" + packageName)
            })
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun openSystemStorage() {
        try {
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openFile(entry: FileEntry) {
        try {
            val original = Uri.parse(entry.id)
            val shareUri = if (original.scheme == "file") {
                FileProvider.getUriForFile(
                    this,
                    packageName + ".fileprovider",
                    File(requireNotNull(original.path))
                )
            } else original
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareUri, entry.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (failure: ActivityNotFoundException) {
            (application as MeowMeowApp).errorLog.record(ErrorArea.EXTERNAL_FILE_OPEN, failure, operation = ErrorOperation.EXTERNAL_OPEN)
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show()
        } catch (failure: Exception) {
            (application as MeowMeowApp).errorLog.record(ErrorArea.EXTERNAL_FILE_OPEN, failure, operation = ErrorOperation.EXTERNAL_OPEN)
            Toast.makeText(this, "Unable to open this file", Toast.LENGTH_LONG).show()
        }
    }
}
