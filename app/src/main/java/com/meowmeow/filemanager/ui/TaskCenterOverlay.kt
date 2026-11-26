package com.meowmeow.filemanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Collapsed by default. An active task never depends on this panel staying open. */
@Composable
fun TaskCenterOverlay(
    state: TaskCenterState,
    onOpen: (BackgroundTaskKind) -> Unit,
    onStop: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onKeepConflict: () -> Unit,
    onStopConflict: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    if (state.tasks.isNotEmpty() || state.notice != null) {
        Column(
            modifier = modifier.padding(end = 12.dp, bottom = 12.dp).widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (expanded) {
                Card(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    Column(
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Tasks", style = MaterialTheme.typography.titleMedium)
                        state.notice?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onDismissNotice) { Text("Dismiss message") }
                        }
                        if (state.tasks.isEmpty()) {
                            Text("No tasks", style = MaterialTheme.typography.bodySmall)
                        }
                        state.tasks.asReversed().forEach { task ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(task.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when (task.status) {
                                            BackgroundTaskStatus.RUNNING -> "Running"
                                            BackgroundTaskStatus.STOPPING -> "Stopping"
                                            BackgroundTaskStatus.COMPLETED -> "Complete"
                                            BackgroundTaskStatus.STOPPED -> "Stopped"
                                            BackgroundTaskStatus.FAILED -> "Failed"
                                            BackgroundTaskStatus.OUTDATED -> "Outdated"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(
                                    "${task.files} items · ${task.folders} folders" +
                                        (if (task.hashed > 0) " · ${task.hashed} hashed" else ""),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { expanded = false; onOpen(task.kind) }) {
                                        Text("Open")
                                    }
                                    if (task.status == BackgroundTaskStatus.RUNNING) {
                                        TextButton(onClick = { onStop(task.id) }) { Text("Stop") }
                                    } else if (!task.active) {
                                        TextButton(onClick = { onDismiss(task.id) }) {
                                            Text("Dismiss")
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
            Card(onClick = { expanded = !expanded }) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    if (expanded) {
                        Text("Hide tasks", style = MaterialTheme.typography.labelLarge)
                    } else if (state.runningCount > 0) {
                        Text(
                            state.runningCount.toString(),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }

    state.conflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onKeepConflict,
            title = { Text("Operation conflict") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cannot start: " + conflict.action)
                    Text(conflict.description)
                    Text(
                        "${conflict.conflictingTaskIds.size} active task(s) may be affected. " +
                            "The new action will not run automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "You can keep the current tasks, or stop them and manually " +
                            "try this action again after they finish stopping.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = onStopConflict) { Text("Stop tasks") }
            },
            dismissButton = {
                TextButton(onClick = onKeepConflict) { Text("Keep tasks") }
            }
        )
    }
}
