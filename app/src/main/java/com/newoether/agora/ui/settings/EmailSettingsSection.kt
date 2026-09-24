package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Email section of the Automation settings page: connected account list with
 * per-account state, poll interval picker, manual refresh, and account removal.
 * Account connection itself happens in chat via the `setup_email` tool — this
 * section only manages already-connected accounts.
 *
 * Extracted to its own file so [SettingsAutomationPage] stays within the
 * 999-line source policy.
 */
@Composable
fun EmailSection(viewModel: ChatViewModel) {
    val accounts by viewModel.settings.emailAccounts.collectAsState()
    val pollIntervalMinutes by viewModel.settings.emailPollIntervalMinutes.collectAsState()
    val pendingCount by viewModel.emailUi.emailPendingCount.collectAsState(initial = 0)
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showPollIntervalDialog by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveAccountId by remember { mutableStateOf<String?>(null) }

    SettingsGroup(
        title = stringResource(R.string.email_settings_title),
        items = listOf(
            // Connected accounts (or the connect-in-chat hint when none)
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.email_accounts)) },
                    supportingContent = {
                        Column {
                            if (accounts.isEmpty()) {
                                Text(stringResource(R.string.email_accounts_empty))
                            } else {
                                Text(
                                    stringResource(R.string.email_accounts_count, accounts.size),
                                )
                                for (account in accounts) {
                                    Text(
                                        text = "• ${account.email}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (pendingCount > 0) {
                                Text(
                                    stringResource(R.string.email_status_queued, pendingCount),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {},
                )
            },
            // Poll interval
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.email_poll_interval)) },
                    supportingContent = {
                        Text(stringResource(R.string.email_poll_interval_desc, pollIntervalMinutes))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        Text(
                            when (pollIntervalMinutes) {
                                0 -> stringResource(R.string.email_interval_never)
                                else -> "$pollIntervalMinutes min"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    modifier = Modifier.clickable { showPollIntervalDialog = true },
                )
            },
            // Manual refresh
            {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.email_status)) },
                    supportingContent = { Text(stringResource(R.string.email_status_desc)) },
                    leadingContent = {
                        Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                refreshing = true
                                viewModel.emailUi.refreshEmailNow()
                                scope.launch {
                                    delay(1_500)
                                    refreshing = false
                                }
                            },
                        ) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.email_refresh_now),
                                )
                            }
                        }
                    },
                )
            },
        ),
    )

    // Per-account removal rows live outside the fixed group so the list can grow.
    if (accounts.isNotEmpty()) {
        SettingsGroup(
            title = stringResource(R.string.email_manage_accounts),
            items = accounts.map { account ->
                {
                    SettingsItem(
                        headlineContent = { Text(account.email) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.email_account_imap,
                                    account.imapHost,
                                    account.imapPort,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Email, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            IconButton(
                                onClick = { confirmRemoveAccountId = account.id },
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(
                                        R.string.email_account_remove,
                                    ),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    if (showPollIntervalDialog) {
        val presets = listOf(0, 5, 15, 30, 60)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showPollIntervalDialog = false },
            title = {
                Text(
                    stringResource(R.string.email_poll_interval),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(presets, key = { it }) { minutes ->
                        val label = when (minutes) {
                            0 -> stringResource(R.string.email_interval_never)
                            else -> "$minutes min"
                        }
                        SettingsItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = pollIntervalMinutes == minutes,
                                    onClick = {
                                        viewModel.settings.saveEmailPollIntervalMinutes(minutes)
                                        showPollIntervalDialog = false
                                    },
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.saveEmailPollIntervalMinutes(minutes)
                                showPollIntervalDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPollIntervalDialog = false }) {
                    Text(stringResource(R.string.provider_close))
                }
            },
        )
    }

    if (confirmRemoveAccountId != null) {
        val accountToRemove = accounts.firstOrNull { it.id == confirmRemoveAccountId }
        if (accountToRemove != null) {
            AlertDialog(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                onDismissRequest = { confirmRemoveAccountId = null },
                title = {
                    Text(
                        stringResource(R.string.email_account_remove_confirm_title),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                },
                text = {
                    Text(stringResource(R.string.email_account_remove_confirm_body, accountToRemove.email))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.settings.removeEmailAccount(accountToRemove.id)
                            confirmRemoveAccountId = null
                        },
                    ) {
                        Text(
                            stringResource(R.string.email_account_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoveAccountId = null }) {
                        Text(stringResource(R.string.provider_close))
                    }
                },
            )
        }
    }
}
