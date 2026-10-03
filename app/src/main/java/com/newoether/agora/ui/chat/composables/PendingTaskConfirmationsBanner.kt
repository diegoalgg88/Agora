package com.newoether.agora.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.data.local.TaskConfirmationEntity
import com.newoether.agora.service.TaskPromptNotifier
import com.newoether.agora.ui.automation.TaskConfirmationActivity
import kotlinx.coroutines.launch

/**
 * Banner that surfaces PENDING rich task confirmations above the composer, next to the
 * SMS/email/assistant banners (plan PLAN-20261002-TASK-CONFIRM).
 *
 * Same pattern as [PendingAssistantActionsBanner]: reads the process-scoped store straight
 * from the application container — the flow is collected only while the chat screen is
 * composed (never at startup, never from the conversation list), so the load-performance
 * invariant holds. The banner stays functional even when the feature toggle is OFF:
 * pre-existing PENDING rows remain resolvable so no row is ever orphaned.
 */
@Composable
fun PendingTaskConfirmationsBanner(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember {
        (context.applicationContext as? AgoraApplication)?.requireContainer()
    }
    val rows by (container?.taskConfirmationStore?.pendingConfirmations
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList<TaskConfirmationEntity>())
        ).collectAsState(initial = emptyList())

    AnimatedVisibility(
        visible = rows.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in rows) {
                // Stable key so a resolution on one row doesn't recompose siblings.
                key(row.id) {
                    TaskConfirmationBannerRow(
                        row = row,
                        onView = {
                            context.startActivity(
                                android.content.Intent(
                                    context,
                                    TaskConfirmationActivity::class.java,
                                ).apply {
                                    putExtra(TaskConfirmationActivity.EXTRA_CONFIRMATION_ID, row.id)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        },
                        onConfirm = {
                            val store = container?.taskConfirmationStore
                            if (store != null) {
                                scope.launch {
                                    store.acknowledge(row.id)
                                    // Cancel even when another surface won the race (no dead button).
                                    container?.taskPromptNotifier?.cancel(row.id)
                                }
                            }
                        },
                        onDismiss = {
                            val store = container?.taskConfirmationStore
                            if (store != null) {
                                scope.launch {
                                    store.dismiss(row.id)
                                    container?.taskPromptNotifier?.cancel(row.id)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskConfirmationBannerRow(
    row: TaskConfirmationEntity,
    onView: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                // Source-aware header: HEARTBEAT localizes; TASK/LOOP show their row title
                // (the task's own name, staged by F8).
                text = com.newoether.agora.service.TaskPromptNotifier(LocalContext.current)
                    .displayTitleFor(row.sourceType, row.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.bodyText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onView) {
                    Text(stringResource(R.string.task_confirmation_banner_view))
                }
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.task_confirmation_banner_dismiss))
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.task_confirmation_banner_confirm))
                }
            }
        }
    }
}
