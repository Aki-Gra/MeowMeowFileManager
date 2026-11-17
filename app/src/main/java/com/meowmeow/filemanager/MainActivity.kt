package com.meowmeow.filemanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.ui.ExplorerScreen
import com.meowmeow.filemanager.ui.ExplorerViewModel
import com.meowmeow.filemanager.ui.theme.MeowMeowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val explorer = ViewModelProvider(
            this, ExplorerViewModel.Factory(applicationContext)
        )[ExplorerViewModel::class.java]

        setContent {
            val state by explorer.state.collectAsStateWithLifecycle()
            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) explorer.chooseTree(uri) }

            MeowMeowTheme {
                ExplorerScreen(
                    state = state,
                    onChooseFolder = { folderPicker.launch(null) },
                    onOpenDirectory = explorer::openDirectory,
                    onOpenFile = ::openFile,
                    onNavigateUp = explorer::navigateUp,
                    onRefresh = explorer::refresh,
                    onSort = explorer::setSortMode,
                    onCreateFolder = explorer::createFolder,
                    onRename = explorer::rename,
                    onDelete = explorer::delete,
                    onDismissError = explorer::clearError
                )
            }
        }
    }

    private fun openFile(entry: FileEntry) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(entry.id), entry.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Unable to access this file", Toast.LENGTH_LONG).show()
        }
    }
}
