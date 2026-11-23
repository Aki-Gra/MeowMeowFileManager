package com.meowmeow.filemanager.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.CopyConflict
import com.meowmeow.filemanager.model.CopyConflictChoice
import com.meowmeow.filemanager.model.isValidFileName

/** Per-conflict prompt, never a hidden apply-to-all replacement. */
@Composable
fun CopyConflictDialog(
    conflict: CopyConflict?,
    onDecision: (CopyConflictChoice) -> Unit
) {
    if (conflict == null) return
    var newName by remember(conflict) { mutableStateOf(conflict.suggestedName) }
    AlertDialog(
        onDismissRequest = { onDecision(CopyConflictChoice.Skip) },
        title = { Text("Name already exists") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    conflict.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text("An item with this name already exists in the destination. Choose what to do with this copy.")
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Name for the new copy") },
                    isError = !isValidFileName(newName)
                )
                if (conflict.canReplace) {
                    Text(
                        "Replace will permanently overwrite the existing file after the new copy is verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Safe replacement is unavailable here. Use Rename or Skip. Folder replacement and provider-based replacement are disabled to protect existing files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isValidFileName(newName) && newName != conflict.name,
                    onClick = { onDecision(CopyConflictChoice.Rename(newName)) }
                ) { Text("Keep both (rename copy)") }
                if (conflict.canReplace) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onDecision(CopyConflictChoice.Replace) }
                    ) { Text("Replace existing file", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onDecision(CopyConflictChoice.Skip) }
                ) { Text("Skip this item") }
            }
        },
        dismissButton = {}
    )
}
