package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.ProjectEntity
import com.newoether.agora.ui.components.clearFocusOnTap

/** Create / rename dialog for a project. Save is disabled while the name is blank. */
@Composable
internal fun ProjectNameDialog(
    titleRes: Int,
    confirmRes: Int,
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.project_name_label)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Picks the destination project for the pending conversations, or starts a new one. */
@Composable
internal fun MoveToProjectDialog(
    projects: List<ProjectEntity>,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_move_to), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (projects.isEmpty()) {
                    Text(
                        stringResource(R.string.project_none_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(projects, key = { it.id }) { project ->
                            ProjectPickerRow(
                                icon = { Icon(Icons.Default.Folder, null, modifier = Modifier.size(20.dp)) },
                                label = project.name,
                                onClick = { onPick(project.id) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                ProjectPickerRow(
                    icon = { Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(20.dp)) },
                    label = stringResource(R.string.project_new),
                    onClick = onCreateNew,
                    emphasized = true,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ProjectPickerRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
        ) { icon() }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Confirms deleting a project; its chats are kept. */
@Composable
internal fun ProjectDeleteDialog(
    projectName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_delete_title), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(projectName, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.project_delete_confirm))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Confirms deleting the (already filtered) set of deletable selected chats. */
@Composable
internal fun BulkDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.drawer_delete_selected_title, count), fontWeight = FontWeight.Bold)
        },
        text = { Text(stringResource(R.string.drawer_delete_selected_confirm)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = count > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
