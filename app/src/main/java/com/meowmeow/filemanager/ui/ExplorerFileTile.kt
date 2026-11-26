package com.meowmeow.filemanager.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.reader.ReaderFormat
import com.meowmeow.filemanager.ui.resultUnlessCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ExplorerFileTile(
    entry: FileEntry,
    mode: ExplorerViewMode,
    thumbnails: ExplorerThumbnails,
    selectionMode: Boolean,
    selected: Boolean,
    actionsEnabled: Boolean,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val rounding = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(rounding)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .clickable(onClick = if (selectionMode) onSelect else onOpen)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            contentAlignment = Alignment.Center
        ) {
            if (mode == ExplorerViewMode.THUMBNAILS && ExplorerThumbnails.shouldPreview(entry)) {
                ImageThumbnail(entry, thumbnails, Modifier.fillMaxWidth().height(108.dp))
            } else {
                val imageVector = when {
                    entry.isDirectory -> Icons.Default.Folder
                    ReaderFormat.detect(entry.name, entry.mimeType) == ReaderFormat.IMAGE ->
                        Icons.Default.ImageIcon
                    entry.mimeType?.startsWith("video/") == true -> Icons.Default.Movie
                    entry.mimeType?.startsWith("audio/") == true -> Icons.Default.AudioFile
                    entry.mimeType == "application/pdf" -> Icons.Default.Description
                    else -> Icons.Default.InsertDriveFile
                }
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(70.dp)
                )
            }
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            } else if (actionsEnabled) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions for " + entry.name)
                    }
                    FileActionsMenu(
                        entry = entry,
                        expanded = menu,
                        onDismiss = { menu = false },
                        onCopy = onCopy,
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete
                    )
                }
            }
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = if (entry.isDirectory) "Folder" else tileFileSize(entry.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun ImageThumbnail(
    entry: FileEntry,
    thumbnails: ExplorerThumbnails,
    modifier: Modifier = Modifier
) {
    val result by produceState<android.graphics.Bitmap?>(
        initialValue = null, entry.id, entry.sizeBytes, entry.modifiedAtMillis
    ) {
        val decoded = resultUnlessCancelled {
            withContext(Dispatchers.IO) { thumbnails.load(entry) }
        }
        value = decoded.getOrNull()
    }
    if (result == null) {
        Icon(
            Icons.Default.ImageIcon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    } else {
        Image(
            bitmap = result!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    }
}

private fun tileFileSize(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    var size = bytes.toDouble()
    var unit = 0
    val labels = arrayOf("B", "KB", "MB", "GB", "TB")
    while (size >= 1024 && unit < labels.lastIndex) {
        size /= 1024
        unit++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", size, labels[unit])
}
