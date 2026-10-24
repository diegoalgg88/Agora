package com.newoether.agora.webui

import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.ui.components.parseLatexSpans
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Un math span en [WebText.markdown] es `MATH_OPEN + index + MATH_CLOSE`. */
internal const val MATH_OPEN = '\uE000'
internal const val MATH_CLOSE = '\uE001'

/**
 * Markdown con el math ya separado por el [parseLatexSpans] de la app, así el navegador detecta
 * fórmulas exactamente como la app. Los caracteres private-use ya presentes en el texto se
 * reemplazan para que no puedan confundirse con un placeholder.
 */
internal fun String.toWebText(parseInlineDollarMath: Boolean): WebText {
    val spans = parseLatexSpans(this, parseInlineDollarMath)
    val math = mutableListOf<WebMath>()
    val markdown = buildString {
        spans.forEach { span ->
            if (span.isLatex) {
                append(MATH_OPEN).append(math.size).append(MATH_CLOSE)
                math += WebMath(span.content, span.display)
            } else {
                append(span.content.replace(MATH_OPEN, '\uFFFD').replace(MATH_CLOSE, '\uFFFD'))
            }
        }
    }
    return WebText(markdown, math)
}

internal fun ChatConversation.toWeb(generating: Boolean) = WebConversation(
    id = id,
    title = title,
    generating = generating,
    unread = hasUnreadGeneration,
)

internal fun ChatMessage.toWebPathEntry() = WebPathEntry(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
)

internal fun ChatMessage.toWeb(inlineDollarMath: Boolean, presentation: WebPresentation?) = WebMessage(
    id = id,
    parentId = parentId,
    participant = participant.name,
    status = status.name,
    timestamp = timestamp,
    modelName = modelName,
    // El bubble del usuario muestra texto plano, así su math no se separa.
    text = if (participant == Participant.USER) WebText(text) else text.toWebText(inlineDollarMath),
    thoughts = thoughts?.toWebText(inlineDollarMath),
    thoughtTitle = thoughtTitle,
    thoughtTimeMs = thoughtTimeMs,
    segments = segments.orEmpty().map { it.toWeb(inlineDollarMath) },
    presentation = presentation,
)

internal fun MessageSegment.toWeb(inlineDollarMath: Boolean) = WebSegment(
    type = type,
    content = content.toWebText(inlineDollarMath),
    durationMs = durationMs,
    toolName = toolName,
    toolDisplayName = toolDisplayName,
    toolState = toolState,
    errorCode = errorCode,
)

@Serializable
internal data class WebSyncCommand(
    val type: String,
    /** Identifica la conexión que envía el comando; los comandos de otras conexiones se ignoran. */
    val connectionId: String = "",
    val conversationId: String? = null,
    val messageIds: List<String> = emptyList(),
    /** La secuencia del request open del navegador, repetida en [WebSyncEvent.Opened]. */
    val seq: Long = 0L,
    val text: String? = null,
    val revision: Long = 0L,
    val actionId: Long = 0L,
    val modelId: String? = null,
    val queuedId: String? = null,
    /** Comandos de attachment: el id del attachment en el draft del composer. */
    val attachmentId: String? = null,
    val pages: List<Int> = emptyList(),
    val frameCount: Int? = null,
    val intervalMs: Long? = null,
    /** Pin de conversación: activar o desactivar. */
    val enabled: Boolean? = null,
    /** Comando setting: el control del tools menu que cambia. */
    val setting: String? = null,
    /** Comando setting: valor string (thinkingLevel/openAiServiceTier). */
    val value: String? = null,
    /** Editor advanced: el draft de los seis parámetros validado server-side. */
    val parameters: com.newoether.agora.data.ConversationSettings? = null,
    /** Editor compact: cuántos mensajes recientes retener. */
    val retainCount: Int? = null,
    /** Interacciones: id de la pregunta o shell pendiente que este comando resuelve. */
    val requestId: String? = null,
    /** Shell decision: confiar siempre en este server. */
    val alwaysAllow: Boolean? = null,
    /** Comandos de fila: la fila que edit/regenerate/delete nombra. */
    val messageId: String? = null,
    /** list_more: el límite de página que el navegador vio en el último evento. */
    val tokens: Long? = null,
    /** Interacciones: respuestas de question_submit, keyed por id de pregunta. */
    val answers: Map<String, QuestionAnswers>? = null,
) {
    /** Respuesta de una pregunta; [answered] distingue skip de submit vacío. */
    @Serializable
    data class QuestionAnswers(
        val choices: List<String> = emptyList(),
        val text: String? = null,
        val answered: Boolean = false,
    )
}

@Serializable
internal sealed interface WebSyncEvent {
    /** Identifica esta conexión para los POST de upload; viaja primero, tras el display. */
    @Serializable @SerialName("connection")
    data class Connection(val connectionId: String) : WebSyncEvent

    @Serializable @SerialName("conversations")
    data class Conversations(
        val items: List<WebConversation>,
        /** True cuando el query trajo más filas que el límite actual del drawer. */
        val hasMore: Boolean = false,
        /** El límite que produjo esta página; el navegador lo repite en list_more. */
        val limit: Int = 0,
    ) : WebSyncEvent

    /** La sesión siguió un movimiento del runtime; [seq] es el open request del navegador que sigue vigente. */
    @Serializable @SerialName("opened")
    data class Opened(val conversationId: String?, val seq: Long) : WebSyncEvent

    /** La rama seleccionada de la conversación abierta, sin cuerpos de mensajes. */
    @Serializable @SerialName("path")
    data class Path(
        val conversationId: String,
        val messages: List<WebPathEntry>,
        val generating: Boolean,
    ) : WebSyncEvent

    /** El cuerpo de una fila observada. */
    @Serializable @SerialName("payload")
    data class Payload(val conversationId: String, val message: WebMessage) : WebSyncEvent

    /** El mensaje en vuelo; reemplaza el cuerpo durable de la fila con el mismo id. */
    @Serializable @SerialName("streaming")
    data class Streaming(val conversationId: String, val message: WebMessage?) : WebSyncEvent

    @Serializable @SerialName("deleted")
    data class Deleted(val conversationId: String) : WebSyncEvent

    /**
     * Cómo el owner de conversación del teléfono respondió al delete de ESTA conexión.
     * [actionId] repite el request: solo el navegador que preguntó settlea su propio diálogo;
     * un delete iniciado por otro cliente llega aquí como [Deleted] y una lista más corta.
     */
    @Serializable @SerialName("conversation_delete")
    data class ConversationDelete(
        val conversationId: String,
        val actionId: Long,
        val success: Boolean,
    ) : WebSyncEvent

    @Serializable @SerialName("load_failed")
    data class LoadFailed(val conversationId: String) : WebSyncEvent

    /** Fase de submisión del composer que el navegador muestra; [acceptedVersion] crece por send aceptado. */
    @Serializable @SerialName("composer")
    data class Composer(
        val conversationId: String?, val phase: String, val acceptedVersion: Long,
        val seq: Long, val text: String, val editRevision: Long, val actionId: Long,
        val modelValid: Boolean, val generating: Boolean, val stopping: Boolean,
        /** Modelo activo del target y el catálogo etiquetado que el selector del navegador lista. */
        val modelId: String, val models: Map<String, String>,
        /** Cola de guidance pendiente: id, texto y conteo de attachments por entrada. */
        val queue: List<JsonObject>,
        /** Proyección de attachments del draft: id, tipo, estado, configuración y previews. */
        val attachments: List<JsonObject> = emptyList(),
        /** Controles efectivos + capabilities resueltas del modelo activo; null mientras carga. */
        val controls: JsonObject? = null,
        /** Overrides/defaults de los seis parámetros de generación + presets. */
        val advanced: JsonObject? = null,
        /** Configuración del editor manual de Compact; null en New Chat. */
        val compact: JsonObject? = null,
    ) : WebSyncEvent

    @Serializable @SerialName("snackbar")
    data class Snackbar(val message: String) : WebSyncEvent

    /**
     * Interacciones visibles para la conversación que este navegador muestra: preguntas ask_user
     * y confirmaciones de shell pendientes. [actionId] crece con cada acción consumida para que
     * el navegador descarte duplicados; [items] serializa kind/id/question/options por pregunta.
     */
    @Serializable @SerialName("interactions")
    data class Interactions(
        val conversationId: String?,
        val seq: Long,
        val actionId: Long,
        val items: List<JsonObject>,
    ) : WebSyncEvent

    /** Matches de búsqueda dentro de la conversación abierta; [revision] descarta respuestas viejas. */
    @Serializable @SerialName("conversation_search")
    data class ConversationSearch(
        val connectionId: String,
        val conversationId: String,
        val seq: Long,
        val revision: Long,
        val query: String,
        val searching: Boolean,
        val matches: List<JsonObject>? = null,
        val failed: Boolean = false,
    ) : WebSyncEvent

    /** Resultado identificado de un fork/share: el navegador descarta duplicados por actionId. */
    @Serializable @SerialName("page_action")
    data class PageAction(
        val conversationId: String,
        val seq: Long,
        val actionId: Long,
        val action: String,
        val success: Boolean,
        val sharedText: String? = null,
    ) : WebSyncEvent

    /** Resultado de la búsqueda global de conversaciones; [revision] descarta respuestas viejas. */
    @Serializable @SerialName("search")
    data class Search(
        val connectionId: String,
        val revision: Long,
        val query: String,
        val searching: Boolean,
        val items: List<JsonObject> = emptyList(),
        val failed: Boolean = false,
    ) : WebSyncEvent

    /**
     * El context accounting de la conversación que este navegador muestra: presupuesto, estimado,
     * las partes que el provider recibe y la cola que la compactación automática mantiene libre.
     * Cada conexión proyecta por su cuenta; la cifra de un target no se muestra bajo otro.
     */
    @Serializable @SerialName("context")
    data class Context(
        val conversationId: String?,
        val seq: Long,
        val tokenBudget: Int,
        val budgetLabel: String,
        val estimatedTokens: Int?,
        val estimatedLabel: String?,
        val parts: List<WebContextPart>,
        val compactThresholdPercent: Int,
        val compactEnabled: Boolean,
        val overCompactThreshold: Boolean,
        val loading: Boolean,
        val failed: Boolean,
    ) : WebSyncEvent

    /** Todo de la conversación abierta; null en la lista significa que no hay lista. */
    @Serializable @SerialName("todo")
    data class Todo(
        val conversationId: String?,
        val seq: Long,
        val items: List<com.newoether.agora.model.ConversationTodoItem>,
    ) : WebSyncEvent

    @Serializable @SerialName("scroll_to_bottom")
    data class ScrollToBottom(val conversationId: String, val messageId: String, val seq: Long) : WebSyncEvent

    /**
     * Handshake visual de un branch replacement: el navegador proyecta el fade/scroll de la
     * composición saliente y confirma con branch_fade/branch_scroll/branch_complete; el
     * coordinator de la sesión sigue siendo el owner del lifetime de la transición.
     */
    @Serializable @SerialName("branch_replacement")
    data class BranchReplacement(
        val connectionId: String,
        val conversationId: String,
        val seq: Long,
        val request: JsonObject? = null,
    ) : WebSyncEvent

    /**
     * Shim del presentation block (c6b2c8ef, diferido): el frontend lee display.reduceMotion SIN
     * optional chaining en el menú del selector (shell.js:440) y crashea si el evento no llega.
     * Los modos de tools/thinking alimentan los títulos de cards cuando llegue el bloque real.
     */
    @Serializable @SerialName("display")
    data class Display(
        val toolCallDisplayMode: String,
        val thinkingSegmentDisplayMode: String,
        val autoExpandActiveGroup: Boolean,
        val autoWrapCodeBlocks: Boolean,
        val blurEffectsEnabled: Boolean,
        val reduceMotion: Boolean,
        val liveThinking: WebLiveTimerStrings,
    ) : WebSyncEvent
}

@Serializable
internal data class WebConversation(
    val id: String,
    val title: String,
    val generating: Boolean,
    val unread: Boolean,
    val isPinned: Boolean = false,
)

@Serializable
internal data class WebPathEntry(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
)

@Serializable
internal data class WebMessage(
    val id: String,
    val parentId: String?,
    val participant: String,
    val status: String,
    val timestamp: Long,
    val modelName: String?,
    val text: WebText,
    val thoughts: WebText?,
    val thoughtTitle: String?,
    val thoughtTimeMs: Long?,
    val segments: List<WebSegment>,
    /** Cómo la app acomoda un mensaje del modelo; null para mensajes del usuario. */
    val presentation: WebPresentation? = null,
)

@Serializable
internal data class WebSegment(
    val type: String,
    val content: WebText,
    val durationMs: Long?,
    val toolName: String?,
    val toolDisplayName: String?,
    val toolState: String?,
    val errorCode: String?,
)

@Serializable
internal data class WebText(val markdown: String, val math: List<WebMath> = emptyList())

@Serializable
internal data class WebMath(val tex: String, val display: Boolean)
