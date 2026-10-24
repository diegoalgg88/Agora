package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.ui.chat.bottombar.contextUsageExceedsCompactThreshold
import com.newoether.agora.viewmodel.ConversationContextProjector
import com.newoether.agora.viewmodel.ConversationContextProjection
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationRequestBuilder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Lo que un navegador pide precioar: la conversación que muestra más el modelo, ventana y prompt
 * de sistema que eligió. La topología de la rama NO va en el request: un navegador vuelve a
 * preguntar cada vez que su propio path visible cambia, y elegir otra rama es exactamente eso,
 * así el path ya carga la información sin copiar la key de proyección del teléfono.
 */
internal data class WebContextAccountingRequest(
    val conversationId: String?,
    val seq: Long,
    val selectedModelId: String,
    val tokenBudget: Int,
    val systemPromptId: String?,
)

/**
 * Tokens que la compactación automática mantiene libres al final de la ventana: el complemento
 * del threshold. La misma aritmética de [contextUsageExceedsCompactThreshold] para que la barra
 * y el estado de advertencia cambien en el mismo token.
 */
internal fun contextReservedTokens(tokenBudget: Int, thresholdPercent: Int): Int {
    val budget = tokenBudget.coerceAtLeast(1)
    val percent = thresholdPercent.coerceIn(50, 100)
    val threshold = ((budget.toLong() * percent + 99L) / 100L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return (budget - threshold).coerceAtLeast(0)
}

@Serializable
internal data class WebContextPart(
    val key: String,
    val tokens: Int,
    val label: String,
)

/**
 * Una proyección como la ve el navegador, con la aritmética de la bottom bar del teléfono: las
 * tres partes que el provider recibe, la cola que la compactación automática mantiene libre y el
 * resto intermedio.
 *
 * Una proyección conserva su cifra anterior mientras re-pricea y no dice de qué target venía esa
 * cifra, así que [settledConversationId] — el target cuya última cifra completada reportó esta
 * conexión — decide si la cifra puede seguir mostrándose. Sin él, abrir otra conversación
 * haría destellar el total de la anterior bajo la nueva.
 */
internal fun contextAccountingEvent(
    request: WebContextAccountingRequest,
    projection: ConversationContextProjection,
    settledConversationId: String?,
    compactThresholdPercent: Int,
    compactEnabled: Boolean,
): WebSyncEvent.Context {
    val settled = projection.conversationId == request.conversationId &&
        (projection.completed || settledConversationId == request.conversationId)
    val usage = projection.usage.takeIf { settled && !projection.failed }
    val budget = request.tokenBudget
    val system = usage?.systemPromptTokens ?: 0
    val tools = usage?.toolTokens ?: 0
    val messages = usage?.messageTokens ?: 0
    val used = (system + tools + messages).coerceAtMost(budget)
    val reserved = if (compactEnabled) contextReservedTokens(budget, compactThresholdPercent) else null
    val free = (budget - used - (reserved ?: 0)).coerceAtLeast(0)
    val parts = if (usage == null) emptyList() else listOfNotNull(
        WebContextPart("system", system, ContextBudget.compactLabel(system)),
        WebContextPart("tools", tools, ContextBudget.compactLabel(tools)),
        WebContextPart("messages", messages, ContextBudget.compactLabel(messages)),
        WebContextPart("free", free, ContextBudget.compactLabel(free)),
        reserved?.let { WebContextPart("reserved", it, ContextBudget.compactLabel(it)) },
    )
    val estimated = usage?.estimatedTokenCount
    return WebSyncEvent.Context(
        conversationId = request.conversationId,
        seq = request.seq,
        tokenBudget = budget,
        budgetLabel = ContextBudget.compactLabel(budget),
        estimatedTokens = estimated,
        estimatedLabel = estimated?.let(ContextBudget::compactLabel),
        parts = parts,
        compactThresholdPercent = compactThresholdPercent,
        compactEnabled = compactEnabled,
        overCompactThreshold = estimated != null &&
            contextUsageExceedsCompactThreshold(estimated, budget, compactThresholdPercent),
        loading = projection.loading,
        failed = projection.failed && projection.conversationId == request.conversationId,
    )
}

/**
 * Construye el context accounting de una conexión de navegador.
 *
 * Cada conexión proyecta por su cuenta: un navegador pricea la conversación que muestra y jamás
 * lee la proyección del teléfono ni la de otro navegador. La política de compactación es el
 * settings flow del teléfono, el mismo que lee la bottom bar.
 */
internal class WebUiContextAccounting(
    private val conversations: ConversationRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val generationManager: () -> GenerationManager,
    private val generationErrorFormatter: (String) -> String,
    val compactThresholdPercent: StateFlow<Int>,
    val compactEnabled: StateFlow<Boolean>,
) {
    /**
     * El projector de una conexión. Sus overrides de New Chat vienen del workspace de New Chat de
     * esa conexión, así un navegador priceando la página de New Chat pricea lo que muestra.
     */
    fun open(
        newChatSystemPromptId: () -> String?,
        newChatConversationSettings: () -> ConversationSettings?,
    ) = ConversationContextProjector(
        conversations = conversations,
        requestBuilder = requestBuilder,
        generationManager = generationManager,
        generationErrorFormatter = generationErrorFormatter,
        newChatSystemPromptId = newChatSystemPromptId,
        newChatConversationSettings = newChatConversationSettings,
    )
}
