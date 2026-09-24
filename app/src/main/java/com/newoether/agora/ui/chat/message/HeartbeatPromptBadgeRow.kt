package com.newoether.agora.ui.chat.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R

/**
 * Collapsed presentation of a heartbeat-origin USER prompt: a compact badge row with a
 * one-line preview. Tap expands the full prompt text; tap again collapses. Ephemeral UI
 * state only — never persisted. Same message, different presentation (no dual rendering).
 */
@Composable
internal fun HeartbeatPromptBadgeRow(
    text: String,
    contextAlpha: Modifier,
    showActions: Boolean,
    onLongPress: () -> Unit,
    onShowDelete: () -> Unit,
    onCopy: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.heartbeat_prompt_badge)
    val collapsedLabel = stringResource(R.string.heartbeat_prompt_collapsed, label)
    val expandedLabel = stringResource(R.string.heartbeat_prompt_expanded, label)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .then(contextAlpha)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                hapticFeedbackEnabled = false,
                onClick = { expanded = !expanded },
                onLongClick = onLongPress,
            )
            .semantics { contentDescription = if (expanded) expandedLabel else collapsedLabel },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 200)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MonitorHeart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = if (expanded) 6.dp else 2.dp)
                    .fillMaxWidth(),
            )
            if (showActions && expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(onClick = onCopy)
                            .padding(2.dp),
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(onClick = onShowDelete)
                            .padding(2.dp),
                    )
                }
            }
        }
    }
}
