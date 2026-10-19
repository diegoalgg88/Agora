package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.newoether.agora.ui.chat.interaction.UserInteractionBar
import com.newoether.agora.ui.chat.interaction.userInteractions
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Host del UserInteractionBar sobre el composer. Extraído de ChatApp: ese archivo está al borde
 * del límite físico de 999 líneas y el bar es una superficie autocontenida.
 *
 * Preguntas ask_user y confirmaciones de shell comparten esta barra pageable; los requests sin
 * chat propio (task runs) son visibles en cualquier conversación abierta.
 */
@Composable
internal fun ChatUserInteractionBarHost(
    viewModel: ChatViewModel,
    currentConversationId: String?,
) {
    val askUserRequests by viewModel.askUser.requests.collectAsState()
    val pendingShell by viewModel.shellConfirmation.pendingShellCommand.collectAsState()
    UserInteractionBar(
        interactions = userInteractions(currentConversationId, askUserRequests, pendingShell),
        onAnswerQuestion = viewModel.askUser::submit,
        onSkipQuestion = viewModel.askUser::dismiss,
        onShellDecision = { _, allow, alwaysAllow ->
            viewModel.resolveShellConfirmation(allow, alwaysAllow)
        },
        onHeightChanged = { },
    )
}
