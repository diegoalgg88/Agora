package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordina requests de `ask_user` entre el tool loop (que pregunta) y la interaction bar (que
 * responde).
 *
 * Los requests se apilan: conversaciones paralelas y tool calls repetidos pueden estar todos
 * esperando, así que el estado es una lista ordenada en vez de un slot único y cada respuesta
 * lleva el id de su request. Un request suspende el tool call que lo hizo hasta que el usuario
 * responda o lo omita. No hay timeout: una pregunta que el usuario aún no ha visto no debe
 * decidirse sola, y solo cancelar la generación termina la espera sin respuesta.
 */
class AskUserController {
    data class Request(
        val id: Long,
        val conversationId: String?,
        val question: String,
        val options: List<String>,
        val allowMultiple: Boolean,
    )

    /** [answered] es false cuando el usuario omitió el request o ya no estaba esperando. */
    data class Answer(val choices: List<String>, val answered: Boolean) {
        companion object {
            val Unanswered = Answer(emptyList(), answered = false)
        }
    }

    private val _requests = MutableStateFlow<List<Request>>(emptyList())

    /** Todos los requests que aún esperan respuesta, el más viejo primero. */
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()
    private val nextRequestId = AtomicLong(1)

    /**
     * Identifica el numerado de requests de este proceso. Las acciones de notificación lo llevan
     * para que una acción construida por un proceso anterior jamás responda un request creado
     * tras un restart.
     */
    val notificationSessionId: String = java.util.UUID.randomUUID().toString()

    /** Busca un request que aún espera, para callers que solo conservan su id. */
    fun requestById(id: Long): Request? = _requests.value.firstOrNull { it.id == id }

    fun open(
        conversationId: String?,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Request {
        val request = Request(
            id = nextRequestId.getAndIncrement(),
            conversationId = conversationId,
            question = question,
            options = options,
            allowMultiple = allowMultiple,
        )
        waiters[request.id] = CompletableDeferred()
        _requests.update { it + request }
        return request
    }

    /**
     * Suspende hasta que [request] tenga respuesta o sea omitido. La espera no tiene límite;
     * cancelar la generación cancela esta coroutine, y [forget] saca entonces el request del bar.
     */
    suspend fun awaitAnswer(request: Request): Answer {
        val waiter = waiters[request.id] ?: return Answer.Unanswered
        return try {
            waiter.await()
        } finally {
            forget(request.id)
        }
    }

    /**
     * Responde un request. Un id que ya no espera se ignora, así un request no puede responderse
     * dos veces y una respuesta stale no puede decidir un request más nuevo.
     */
    fun submit(id: Long, choices: List<String>) = complete(id, Answer(choices, answered = true))

    /** El usuario declinó responder. El caller en espera resume con [Answer.Unanswered]. */
    fun dismiss(id: Long) = complete(id, Answer.Unanswered)

    private fun complete(id: Long, answer: Answer) {
        _requests.value.firstOrNull { it.id == id } ?: return
        val waiter = waiters[id]
        forget(id)
        waiter?.complete(answer)
    }

    private fun forget(id: Long) {
        waiters.remove(id)
        _requests.update { requests -> requests.filterNot { it.id == id } }
    }
}
