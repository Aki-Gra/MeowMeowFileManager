package com.meowmeow.filemanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.FileEntry
import com.meowmeow.filemanager.model.humanSize

/** Each group header and member row occupies its own lazy-list slot. */
@Composable
internal fun DuplicateGroupHeader(
    group: DuplicateGroupResult,
    selectedIds: Set<String>,
    isDeleting: Boolean,
    onSelectGroup: (List<String>) -> Unit,
    onRequestDelete: (DuplicateGroupResult) -> Unit
) {
    val groupIds = group.members.map { it.entry.id }
    val selectedCount = groupIds.count { it in selectedIds }
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "${group.members.size} identical files · " +
                humanSize(group.sizeBytes) + " each · " +
                humanSize(group.reclaimableBytes) + " potentially reclaimable",
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                enabled = !isDeleting,
                onClick = { onSelectGroup(groupIds) }
            ) {
                Text(
                    if (groupIds.all { it in selectedIds }) "Deselect all"
                    else "Select all copies"
                )
            }
            Button(
                enabled = !isDeleting && selectedCount > 0,
                onClick = { onRequestDelete(group) }
            ) { Text("Delete selected ($selectedCount)") }
        }
    }
}

@Composable
internal fun DuplicateFileRow(
    hit: FileAnalysisHit,
    group: DuplicateGroupResult,
    selected: Boolean,
    isDeleting: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    onToggleDuplicate: (String) -> Unit,
    onRequestSingleDelete: (DuplicateGroupResult, String) -> Unit
) {
    ListItem(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !isDeleting) { onOpenFile(hit.entry) },
        leadingContent = {
            Checkbox(
                checked = selected,
                enabled = !isDeleting,
                onCheckedChange = { onToggleDuplicate(hit.entry.id) }
            )
        },
        headlineContent = {
            Text(hit.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                humanSize(hit.entry.sizeBytes) + " · " + hit.location,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            IconButton(
                enabled = !isDeleting,
                onClick = { onRequestSingleDelete(group, hit.entry.id) }
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete " + hit.entry.name
                )
            }
        }
    )
}
