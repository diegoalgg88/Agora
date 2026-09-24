package com.newoether.agora.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.local.AssistantActionStatus
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Combines the pending-action banners shown above the composer: the SMS draft review,
 * the email draft review (both user-gated sends), and the assistant device-action
 * approval (user-gated alarms and calendar writes). Wraps all so the ChatApp call
 * site stays a single composable.
 */
@Composable
fun PendingDeviceActionBanners(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PendingSmsBanner(viewModel = viewModel)
        PendingEmailDraftBanner(viewModel = viewModel)
        PendingAssistantActionsBanner()
    }
}

/**
 * Stack of cards, one per staged assistant action (alarm / calendar write), mirroring
 * [PendingSmsBanner]. Explicit confirmation gate — the AI stages actions via
 * `set_alarm` / calendar tools but nothing runs on the device until the user taps
 * Approve here. Approve is the only path that dispatches through the tool provider
 * (via AssistantActionStore).
 */
@Composable
fun PendingAssistantActionsBanner(
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val container = remember {
        (context.applicationContext as? AgoraApplication)?.requireContainer()
    }
    val actions by (container?.assistantActionStore?.actions
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.newoether.agora.data.AssistantAction>())
        ).collectAsState(initial = emptyList())
    val activeActions = actions.filter { it.status != AssistantActionStatus.DONE }

    AnimatedVisibility(
        visible = activeActions.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (action in activeActions) {
                // Stable key so a status change on one action doesn't recompose siblings.
                key(action.id) {
                    AssistantActionCard(
                        summary = action.summary,
                        status = action.status,
                        lastError = action.lastError,
                        onApprove = {
                            val store = container?.assistantActionStore
                            val provider = container?.assistantDeviceToolProvider
                            if (store != null && provider != null) {
                                scope.launch { store.approveAction(action.id, provider) }
                            }
                        },
                        onDiscard = {
                            scope.launch { container?.assistantActionStore?.removeAction(action.id) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantActionCard(
    summary: String,
    status: AssistantActionStatus,
    lastError: String?,
    onApprove: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Pending device action",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            when (status) {
                AssistantActionStatus.PENDING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDiscard) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Discard",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Discard")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onApprove) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Approve",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approve")
                    }
                }

                AssistantActionStatus.EXECUTING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Running…",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                AssistantActionStatus.FAILED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = lastError?.let { "Failed: $it" } ?: "Failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onApprove) {
                        Text("Retry")
                    }
                    TextButton(onClick = onDiscard) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Discard",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Discard")
                    }
                }

                AssistantActionStatus.DONE -> Unit // DONE actions are removed from the store.
            }
        }
    }
}
