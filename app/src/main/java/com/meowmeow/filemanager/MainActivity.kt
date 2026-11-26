package com.meowmeow.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.io.IOException
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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

    override fun onResume() {
        super.onResume()
        allFilesGranted = canBrowseSharedStorage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorLog = (application as MeowMeowApp).errorLog
        val scoped = ViewModelProvider(
            this, ExplorerViewModel.Factory(applicationContext)
        )[ExplorerViewModel::class.java]
        val device = ViewModelProvider(
            this, DeviceExplorerViewModel.Factory(applicationContext)
        )[DeviceExplorerViewModel::class.java]
        val cleanup = ViewModelProvider(
            this, CleanupViewModel.Factory(applicationContext)
        )[CleanupViewModel::class.java]
        val analysis = ViewModelProvider(
            this, FileAnalysisViewModel.Factory(applicationContext)
        )[FileAnalysisViewModel::class.java]
        val copy = ViewModelProvider(
            this, CopyViewModel.Factory(applicationContext)
        )[CopyViewModel::class.java]
        val bulk = ViewModelProvider(
            this, BulkViewModel.Factory(applicationContext)
        )[BulkViewModel::class.java]
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        setContent {
            val scopedState by scoped.state.collectAsStateWithLifecycle()
            val deviceState by device.state.collectAsStateWithLifecycle()
            val deviceSearch by device.search.collectAsStateWithLifecycle()
            val scopedSearch by scoped.search.collectAsStateWithLifecycle()
            val cleanupState by cleanup.state.collectAsStateWithLifecycle()
            val analysisState by analysis.state.collectAsStateWithLifecycle()
            val copyState by copy.state.collectAsStateWithLifecycle()
            val bulkState by bulk.state.collectAsStateWithLifecycle()
            val savedErrors by errorLog.entries.collectAsStateWithLifecycle()
            val errorLoggingEnabled by errorLog.enabled.collectAsStateWithLifecycle()
            var page by rememberSaveable { mutableStateOf(Page.FILES) }
            var usingDevice by rememberSaveable { mutableStateOf(canBrowseSharedStorage()) }
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
                if (page == Page.CLEANUP && allFilesGranted) cleanup.scan()
            }

            val browseDevice = {
                if (allFilesGranted) {
                    usingDevice = true
                    device.openRoot()
                    page = Page.FILES
                } else requestAllFilesAccess()
            }
            val browseDownloads = {
                if (allFilesGranted) {
                    usingDevice = true
                    device.openDownloads()
                    page = Page.FILES
                } else requestAllFilesAccess()
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
                                onChooseFolder = { folderPicker.launch(null) },
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
                                    copy.paste(destination) {
                                        if (destinationModeIsDevice) device.refresh()
                                        else scoped.refresh()
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
                                    bulk.confirm {
                                        if (allFilesGranted) device.refresh()
                                        scoped.refresh()
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
                                onRequestFullAccess = ::requestAllFilesAccess,
                                onOpenDeviceStorage = browseDevice,
                                onBrowseDownloads = browseDownloads
                            )
                            Page.STORAGE -> StorageOverviewScreen(
                                hasFullAccess = allFilesGranted,
                                supportsFullAccess = supported,
                                onGrantAccess = ::requestAllFilesAccess,
                                onBrowseDownloads = browseDownloads,
                                onOpenCleanup = { page = Page.CLEANUP },
                                onOpenSystemStorage = ::openSystemStorage
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
                                onBack = {
                                    analysis.reset()
                                    page = Page.CLEANUP
                                },
                                onLeave = analysis::reset,
                                onStart = { kind, scope, minimumMiB ->
                                    val folder = if (usingDevice) device.analysisFolder(scope)
                                        else scoped.analysisFolder(scope)
                                    analysis.scan(folder, kind, scope, minimumMiB)
                                },
                                onCancel = analysis::cancel,
                                onOpenFile = ::openFile,
                                onToggleDuplicate = analysis::toggleDuplicate,
                                onSelectGroup = analysis::selectDuplicateGroup,
                                onRequestDelete = analysis::requestDelete,
                                onRequestSingleDelete = analysis::requestSingleDelete,
                                onConfirmDelete = analysis::confirmDelete,
                                onDismissDelete = analysis::cancelDeleteConfirmation,
                                onCancelDeletion = analysis::cancelDeletion
                            )
                            Page.CLEANUP -> CleanupScreen(
                                state = cleanupState,
                                onOpenAnalysis = { page = Page.ANALYSIS },
                                hasFullAccess = allFilesGranted,
                                supportsFullAccess = supported,
                                onGrantAccess = ::requestAllFilesAccess,
                                onScan = cleanup::scan,
                                onToggle = cleanup::toggleSelected,
                                onDelete = cleanup::deleteSelected,
                                onDismissMessage = cleanup::clearMessage
                            )
                        }
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
