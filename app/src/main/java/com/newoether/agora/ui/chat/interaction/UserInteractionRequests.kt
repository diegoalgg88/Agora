package com.newoether.agora.ui.chat.interaction

import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController

/**
 * Un request pendiente que el usuario debe responder antes de que el tool loop que preguntó
 * pueda continuar.
 *
 * Preguntas y confirmaciones de shell vienen de controllers distintos pero ocupan el mismo
 * espacio en pantalla, así que el bar las trata como una lista ordenada. [key] es estable por
 * request y con prefijo por tipo, porque los dos controllers numeran sus requests de forma
 * independiente.
 */
internal sealed interface UserInteraction {
    val key: String

    data class Question(val request: AskUserController.Request) : UserInteraction {
        override val key: String get() = "question:${request.id}"
    }

    data class ShellCommand(
        val pending: ShellConfirmationController.PendingShellCommand,
    ) : UserInteraction {
        override val key: String get() = "shell:${pending.id}"
    }
}

/**
 * Recolecta los requests que la conversación [conversationId] debe mostrar, el más viejo
 * primero.
 *
 * Una pregunta sin conversation id vino de una superficie sin chat propio (un task run, por
 * ejemplo), así que cualquier conversación puede responderla. Las confirmaciones de shell son
 * process-wide por diseño: solo una pendiente a la vez y cualquier chat visible puede
 * responderla.
 */
internal fun userInteractions(
    conversationId: String?,
    questions: List<AskUserController.Request>,
    shellCommand: ShellConfirmationController.PendingShellCommand?,
): List<UserInteraction> {
    val visibleQuestions = questions
        .filter { it.conversationId == null || it.conversationId == conversationId }
        .map { UserInteraction.Question(it) }
    val shell = shellCommand?.let { UserInteraction.ShellCommand(it) }
    return if (shell == null) visibleQuestions else visibleQuestions + shell
}
