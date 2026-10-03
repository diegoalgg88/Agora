package com.newoether.agora.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.TaskConfirmationEntity
import com.newoether.agora.service.TaskPromptNotifier

/**
 * Bottom-anchored rich confirmation card. Pure presentation: every action is a callback;
 * the activity owns resolution. Body collapses to [COLLAPSED_MAX_LINES] and expands on
 * tap so long heartbeat results stay readable without stealing the whole screen.
 */
@Composable
internal fun TaskConfirmationCard(
    row: TaskConfirmationEntity,
    centered: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: (minutes: Int) -> Unit,
    onOpenConversation: () -> Unit,
) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    // Measured, not guessed: a body of many short lines overflows the line cap without ever
    // reaching a character threshold, so the expand control follows real visual overflow.
    var overflows by remember(row.id) { mutableStateOf(false) }
    var snoozeMenuOpen by remember(row.id) { mutableStateOf(false) }

    Surface(
        // widthIn BEFORE fillMaxWidth: applied after it, the already-fixed incoming width wins
        // and the 560dp cap silently does nothing on wide screens.
        modifier = Modifier
            .then(if (centered) Modifier.widthIn(max = 560.dp) else Modifier)
            .fillMaxWidth()
            .padding(
                horizontal = if (centered) 32.dp else 12.dp,
                vertical = if (centered) 24.dp else 16.dp,
            ),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                // Localized display header; the durable row.title stays neutral by contract.
                text = stringResource(TaskPromptNotifier.titleResFor(row.sourceType)),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
                modifier = Modifier.heightIn(max = if (expanded) 320.dp else 160.dp),
            )
            if (expanded || overflows) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (expanded) R.string.task_confirmation_card_collapse
                            else R.string.task_confirmation_card_expand,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.task_confirmation_action_confirm))
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.task_confirmation_banner_dismiss))
                }
                Box {
                    TextButton(onClick = { snoozeMenuOpen = true }) {
                        Text(stringResource(R.string.task_confirmation_action_snooze))
                    }
                    DropdownMenu(
                        expanded = snoozeMenuOpen,
                        onDismissRequest = { snoozeMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_confirmation_snooze_10)) },
                            onClick = { snoozeMenuOpen = false; onSnooze(10) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_confirmation_snooze_30)) },
                            onClick = { snoozeMenuOpen = false; onSnooze(30) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_confirmation_snooze_60)) },
                            onClick = { snoozeMenuOpen = false; onSnooze(60) },
                        )
                    }
                }
            }
            TextButton(
                onClick = onOpenConversation,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.task_confirmation_action_open_conversation))
            }
        }
    }
}

private const val COLLAPSED_MAX_LINES = 6
