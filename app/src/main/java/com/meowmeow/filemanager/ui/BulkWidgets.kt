package com.meowmeow.filemanager.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowmeow.filemanager.model.BulkAction

@Composable
fun BulkBottomBar(
    state: BulkUiState,
    canPaste: Boolean,
    onAction: (BulkAction) -> Unit,
    onPasteReview: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    if (state.stage == BulkStage.OFF || state.stage == BulkStage.REVIEW) return
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                when (state.stage) {
                    BulkStage.SELECTING -> "Selected: " + state.count + " item(s)"
                    BulkStage.DESTINATION -> "Choose destination for " + state.count + " item(s)"
                    BulkStage.RUNNING -> "Working on " + state.count + " item(s)"
                    BulkStage.RESULTS -> if (state.cancelled) "Cancelled — partial results" else "Bulk operation results"
                    else -> ""
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            when (state.stage) {
                BulkStage.SELECTING -> {
                    Text("Known file size: " + (state.selectedBytes / 1024L) +
                        " KiB (folders not counted)", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(enabled = state.count > 0,
                            onClick = { onAction(BulkAction.COPY) }) { Text("Copy") }
                        Button(enabled = state.count > 0,
                            onClick = { onAction(BulkAction.MOVE) }) { Text("Move") }
                        Button(enabled = state.count > 0,
                            onClick = { onAction(BulkAction.DELETE) }) { Text("Delete") }
                        TextButton(onClick = onDiscard) { Text("Cancel") }
                    }
                }
                BulkStage.DESTINATION -> {
                    Text("Navigate to a writable folder and review before proceeding.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canPaste, onClick = onPasteReview) {
                            Text("Paste here")
                        }
                        TextButton(onClick = onDiscard) { Text("Discard") }
                    }
                }
                BulkStage.RUNNING -> {
                    val p = state.progress
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Completed: " + (p?.processed ?: 0) + "/" + state.count +
                        " · Success: " + (p?.success ?: 0) +
                        " · Skipped: " + (p?.skipped ?: 0) +
                        " · Failed: " + (p?.failed ?: 0),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onCancel) { Text("Cancel remaining") }
                }
                BulkStage.RESULTS -> {
                    val p = state.progress
                    Text(
                        "Success: " + (p?.success ?: 0) +
                        " · Skipped: " + (p?.skipped ?: 0) +
                        " · Failed: " + (p?.failed ?: 0) +
                        " · Not processed: " + (state.count - (p?.processed ?: 0)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    p?.results?.filter { it.status != com.meowmeow.filemanager.model.BulkStatus.SUCCESS }
                        ?.take(5)?.forEach { result ->
                            Text(result.name + ": " + result.detail,
                                maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        }
                    TextButton(onClick = onDiscard) { Text("Done") }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun BulkReviewDialog(
    state: BulkUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    if (state.stage != BulkStage.REVIEW) return
    val action = state.action ?: return
    val verb = when (action) {
        BulkAction.COPY -> "Copy"
        BulkAction.MOVE -> "Move"
        BulkAction.DELETE -> "Permanently delete"
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(verb + " " + state.count + " selected items?") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (action == BulkAction.DELETE) {
                    Text(
                        "This permanently removes the selected files, and all contents of selected folders. There is no recycle bin or undo.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(if (action == BulkAction.MOVE)
                        "Copies are verified before originals are removed. A partial failure may leave items in both locations."
                        else "The original files will not be deleted.")
                    Text("Destination: " + (state.destinationLabel ?: "Not selected"))
                }
                Text("Selected items:", style = MaterialTheme.typography.titleSmall)
                state.selectedNames.take(8).forEach { name ->
                    Text("• " + name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.count > 8) {
                    Text("… and " + (state.count - 8) + " more")
                }
                if (action != BulkAction.DELETE) {
                    Text("Existing destination names are skipped, not overwritten.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(verb) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Back") }
        }
    )
}
