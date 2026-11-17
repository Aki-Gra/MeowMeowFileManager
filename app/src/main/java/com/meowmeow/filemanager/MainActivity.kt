package com.meowmeow.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.ui.CleanupScreen
import com.meowmeow.filemanager.ui.CleanupViewModel
import com.meowmeow.filemanager.ui.DeviceExplorerViewModel
import com.meowmeow.filemanager.ui.ExplorerScreen
import com.meowmeow.filemanager.ui.ExplorerViewModel
import com.meowmeow.filemanager.ui.StorageOverviewScreen
import com.meowmeow.filemanager.ui.theme.MeowMeowTheme
import java.io.File

private enum class Page { FILES, STORAGE, CLEANUP }

class MainActivity : ComponentActivity() {
    private var allFilesGranted by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        allFilesGranted = canBrowseSharedStorage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scoped = ViewModelProvider(
            this, ExplorerViewModel.Factory(applicationContext)
        )[ExplorerViewModel::class.java]
        val device = ViewModelProvider(
            this, DeviceExplorerViewModel.Factory()
        )[DeviceExplorerViewModel::class.java]
        val cleanup = ViewModelProvider(
            this, CleanupViewModel.Factory()
        )[CleanupViewModel::class.java]
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        setContent {
            val scopedState by scoped.state.collectAsStateWithLifecycle()
            val deviceState by device.state.collectAsStateWithLifecycle()
            val cleanupState by cleanup.state.collectAsStateWithLifecycle()
            var page by rememberSaveable { mutableStateOf(Page.FILES) }
            var usingDevice by rememberSaveable { mutableStateOf(canBrowseSharedStorage()) }
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
                if (page == Page.FILES && allFilesGranted && usingDevice) device.refresh()
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
                                selected = page == Page.FILES,
                                onClick = { page = Page.FILES },
                                icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                label = { Text("Files") }
                            )
                            NavigationBarItem(
                                selected = page == Page.STORAGE,
                                onClick = { page = Page.STORAGE },
                                icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                                label = { Text("Storage") }
                            )
                            NavigationBarItem(
                                selected = page == Page.CLEANUP,
                                onClick = { page = Page.CLEANUP },
                                icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                                label = { Text("Cleanup") }
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
                                onDismissError = {
                                    if (usingDevice) device.clearError() else scoped.clearError()
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
                            Page.CLEANUP -> CleanupScreen(
                                state = cleanupState,
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
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open this file", Toast.LENGTH_LONG).show()
        }
    }
}
