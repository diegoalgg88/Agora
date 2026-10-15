package com.newoether.agora.api.util

import com.newoether.agora.api.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Nombre reservado para un tool call que el modelo emitió en una forma que no puede ejecutarse ni
 * emparejarse: un id inválido o duplicado, un tool name ausente o inválido, argumentos que no son
 * un objeto JSON, o un payload de tool en texto que no pudo parsearse.
 *
 * En lugar de fallar todo el Run, la llamada dañada se reemplaza por una llamada emparejable bajo
 * este nombre. El tool executor jamás la ejecuta; responde con un resultado de error que explica
 * qué estaba mal, de modo que el modelo ve el fallo en su propio contexto y puede re-emitir una
 * llamada correcta.
 */
internal const val MALFORMED_TOOL_CALL_NAME = "agora_malformed_tool_call"

private const val ORIGINAL_NAME_MAX_CHARS = 200
private const val ORIGINAL_ARGUMENTS_MAX_CHARS = 2_000

/** Construye el stand-in emparejable de una llamada dañada. [cause] se muestra al modelo verbatim. */
internal fun malformedToolCallRequest(
    cause: String,
    originalName: String? = null,
    originalArguments: String? = null,
    streamKey: String? = null,
    /** Metadata de replay del provider (por ejemplo una thought signature de Gemini) que el stand-in debe conservar. */
    signature: String? = null,
): StreamEvent.ToolCallRequest {
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val arguments = buildJsonObject {
        put("error", cause)
        originalName?.takeIf { it.isNotEmpty() }?.let {
            put("original_name", it.take(ORIGINAL_NAME_MAX_CHARS))
        }
        originalArguments?.takeIf { it.isNotEmpty() }?.let {
            put("original_arguments", it.take(ORIGINAL_ARGUMENTS_MAX_CHARS))
        }
    }
    return StreamEvent.ToolCallRequest(
        id = "call_malformed_$suffix",
        name = MALFORMED_TOOL_CALL_NAME,
        arguments = arguments.toString(),
        signature = signature,
        streamKey = streamKey?.takeIf { it.isNotBlank() } ?: "malformed_tool_$suffix",
    )
}

/** El resultado de error que el modelo recibe por una llamada [MALFORMED_TOOL_CALL_NAME]. */
internal fun malformedToolCallResultText(arguments: String): String {
    val fields = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
    fun field(key: String) = (fields?.get(key) as? JsonPrimitive)?.content
    return buildString {
        append("Error: the previous tool call was malformed and was not executed")
        field("error")?.let { append(". Cause: ").append(it) }
        field("original_name")?.let { append(". Tool name as sent: ").append(it) }
        field("original_arguments")?.let { append(". Arguments as sent: ").append(it) }
        append(
            ". Re-issue the call with one of the offered tool names and a complete JSON object " +
                "as arguments. If the arguments were cut off, send a smaller payload.",
        )
    }
}
