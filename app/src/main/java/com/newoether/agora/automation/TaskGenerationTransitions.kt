package com.newoether.agora.automation

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.RunEffect
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.GenerationFinalizer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Settle durable de un stop de Task/Loop/subagent: lanza la finalización persistente y espera su
 * completion. El contrato es el del stop ordinario: nunca retorna antes de que Room asiente.
 */
internal suspend fun GenerationFinalizer.settleTaskStopEffect(
    state: ConversationGenerationState,
    effect: RunEffect.FinalizeStop,
    messages: List<ChatMessage>,
) = withContext(NonCancellable) {
    launchStopFinalization(
        scope = state.scope,
        identity = effect.identity,
        messages = messages,
    ) { completion ->
        val result = state.finishStopFinalization(completion)
        if (result.accepted && completion.success) state.clearStoppedOverlay()
    }.join()
}
