package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.viewmodel.ChatViewModel

/** Host del capsule: cercado al owner abierto, mismo estrato que el interaction/todo bar. */
@Composable
internal fun ConversationSubagentsBarHost(
    viewModel: ChatViewModel,
    conversationId: String?,
    onSelect: (String) -> Unit,
) {
    val subagents by viewModel.currentSubagents.collectAsState()
    val agents = subagents.second.takeIf { subagents.first == conversationId }.orEmpty()
    ConversationSubagentsBar(conversationId, agents, onSelect = onSelect)
}

/**
 * Solo navegación: las conversaciones y el runtime ordinario own el contenido/estado del
 * capsule. Tocar abre la hija por la selección canónica; la hija sigue siendo un ítem normal
 * de la lista y muestra a sus hermanas con su propio capsule seleccionado.
 */
@Composable
internal fun ConversationSubagentsBar(
    conversationId: String?,
    agents: List<SubagentSummary>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit = {},
) {
    if (conversationId == null || agents.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        items(agents, key = { it.id }) { agent ->
            val selected = agent.id == conversationId
            Surface(
                onClick = { onSelect(agent.id) },
                shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp).widthIn(max = 240.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (agent.status == "running") {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else Icon(
                        when (agent.status) {
                            "completed" -> Icons.Default.CheckCircle
                            "failed" -> Icons.Default.ErrorOutline
                            "stopped" -> Icons.Default.PauseCircleOutline
                            else -> Icons.Default.SmartToy
                        },
                        contentDescription = stringResource(when (agent.status) {
                            "completed" -> R.string.subagent_completed
                            "failed" -> R.string.subagent_failed
                            "stopped" -> R.string.subagent_stopped
                            else -> R.string.subagent_idle
                        }),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(agent.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
