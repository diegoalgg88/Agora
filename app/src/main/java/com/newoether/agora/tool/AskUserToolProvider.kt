package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Deja que el modelo ponga una decisión de vuelta al usuario en vez de adivinar. La interaction
 * bar renderiza la pregunta y la respuesta regresa aquí.
 *
 * La llamada suspende hasta que el usuario responda u omita, así que la respuesta es parte de
 * este tool result y el modelo jamás tiene que adivinar qué se eligió. No hay timeout: el usuario
 * decide cuándo se responde la pregunta, y detener la generación es lo que termina una espera no
 * deseada.
 */
class AskUserToolProvider(private val askUser: AskUserController) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.askUserEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = TOOL_NAME,
                    description = "Ask the user a question they answer by choosing from options " +
                        "you supply. Use it when the decision is the user's to make and guessing " +
                        "would waste work. This call returns only after the user answers or " +
                        "skips, so ask one focused question instead of several in a row.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "question" to ToolProperty(
                                "string",
                                "The question, written in the user's language.",
                            ),
                            "options" to ToolProperty(
                                "array",
                                "The answers to choose from. Keep each one short.",
                                ToolProperty("string", "One selectable answer."),
                            ),
                            "allow_multiple" to ToolProperty(
                                "boolean",
                                "True lets the user pick several options. Defaults to false.",
                            ),
                        ),
                        required = listOf("question", "options"),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == TOOL_NAME

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val args = runCatching { Json.parseToJsonElement(arguments.ifBlank { "{}" }) as JsonObject }
            .getOrNull() ?: return failure("bad_arguments", "Arguments are not a JSON object.")
        val question = args["question"]?.stringOrNull()
            ?: return failure("no_question", "A question is required.")
        val options = (args["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        if (options.isEmpty()) {
            return failure("no_options", "Supply at least one option for the user to choose.")
        }
        val allowMultiple = args["allow_multiple"]?.jsonPrimitive?.booleanOrNull ?: false
        val request = askUser.open(
            conversationId = ctx.conversationId,
            question = question,
            options = options,
            allowMultiple = allowMultiple,
        )
        val answer = askUser.awaitAnswer(request)
        return buildJsonObject {
            put("answered", answer.answered)
            if (answer.answered) {
                put("choices", JsonArray(answer.choices.map(::JsonPrimitive)))
            } else {
                put("detail", "The user did not answer. Do not invent an answer; ask again or continue without it.")
            }
        }.toString()
    }

    private fun failure(code: String, detail: String): String = buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val TOOL_NAME = "ask_user"
    }
}
