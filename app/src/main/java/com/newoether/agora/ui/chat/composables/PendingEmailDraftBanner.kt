package com.newoether.agora.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.EmailDraft
import com.newoether.agora.data.local.EmailDraftStatus
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Stack of cards, one per email draft, showing the full PENDING/SENDING/SENT/FAILED
 * lifecycle. Explicit confirmation gate — the AI stages drafts via `send_email` /
 * `reply_email` but nothing leaves the device until the user taps Send here. The Send
 * action is the only path that dispatches through SmtpClient (via the ViewModel).
 */
@Composable
fun PendingEmailDraftBanner(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val drafts by viewModel.emailUi.emailDrafts.collectAsState(initial = emptyList())
    val activeDrafts = drafts.filter { it.status != EmailDraftStatus.SENT }

    AnimatedVisibility(
        visible = activeDrafts.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (draft in activeDrafts) {
                // Stable key so a status change on one draft doesn't recompose siblings.
                key(draft.id) {
                    EmailDraftCard(
                        draft = draft,
                        onSend = { viewModel.emailUi.sendEmailDraft(draft.id) },
                        onDiscard = { viewModel.emailUi.discardEmailDraft(draft.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmailDraftCard(
    draft: EmailDraft,
    onSend: () -> Unit,
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
                text = stringResource(R.string.email_draft_banner_title, draft.toAddress),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = draft.subject,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = draft.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            when (draft.status) {
                EmailDraftStatus.PENDING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDiscard) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.email_draft_discard),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.email_draft_discard))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSend) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(R.string.email_draft_send),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.email_draft_send))
                    }
                }

                EmailDraftStatus.SENDING -> Row(
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
                        text = stringResource(R.string.email_draft_sending),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onSend) {
                        Text(stringResource(R.string.email_draft_retry))
                    }
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.email_draft_discard))
                    }
                }

                EmailDraftStatus.FAILED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = draft.lastError?.let {
                            stringResource(R.string.email_draft_failed_reason, it)
                        } ?: stringResource(R.string.email_draft_failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSend) {
                        Text(stringResource(R.string.email_draft_retry))
                    }
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.email_draft_discard))
                    }
                }

                EmailDraftStatus.SENT -> Unit // SENT drafts are filtered out above.
            }
        }
    }
}
