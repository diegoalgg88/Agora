package com.newoether.agora.api.openai

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ModelFetchEmptyResultException
import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.decodeModelFetchResponse
import com.newoether.agora.api.requireModelFetchBody
import com.newoether.agora.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * AI Horde chat via the official OpenAI-compatible proxy (https://oai.aihorde.net/v1).
 * Models are served by volunteer workers, so availability and latency vary.
 * An anonymous API key ("0000000000") works at the lowest queue priority; a personal
 * key can be registered at https://aihorde.net/register.
 */
class AiHordeProvider : BaseOpenAiProvider() {
    override val name: String = Constants.PROVIDER_AI_HORDE
    // IMPORTANT: keep the "/v1" segment here, exactly like every other OpenAI-style provider
    // in this codebase (OpenAiProvider, DeepSeekProvider, etc.). BaseOpenAiProvider.generateResponse()
    // is NOT overridden below — it builds the chat endpoint as "$baseUrl/chat/completions" using
    // this defaultBaseUrl directly. If "/v1" is dropped from here, fetchModels() below can still
    // work by hardcoding its own "/v1/models" path, but every actual chat request silently 404s
    // because it hits "https://oai.aihorde.net/chat/completions" instead of the real
    // "https://oai.aihorde.net/v1/chat/completions" — exactly the failure mode this fixes.
    override val defaultBaseUrl: String = "https://oai.aihorde.net/v1"
    // Reasoning/content parsing uses BaseOpenAiProvider's default (reasoning_content + content).

    // The AI Horde OpenAI proxy does not handle null values for optional parameters
    // (temperature, top_p, frequency_penalty, presence_penalty) correctly.
    // Strip null/zero values from the request to avoid 500 errors.
    override fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest {
        val filteredTools = request.tools?.filterNotNull()
        val nonEmptyTools = filteredTools?.takeIf { it.isNotEmpty() }
        return request.copy(
            temperature = request.temperature?.takeIf { it > 0f },
            topP = request.topP?.takeIf { it > 0f },
            frequencyPenalty = request.frequencyPenalty?.takeIf { it != 0f },
            presencePenalty = request.presencePenalty?.takeIf { it != 0f },
            tools = nonEmptyTools,
        )
    }

    // The AI Horde OpenAI proxy builds the completion prompt by running each message through a
    // Jinja chat template (tokenizer.apply_chat_template), and those templates call string
    // methods (e.g. .strip()) directly on message["content"]. Every provider in this codebase
    // sends OpenAI's array-of-parts content shape (content: [{"type":"text","text":"..."}]),
    // which is valid OpenAI wire format but is not a string. Running the template against a list
    // throws inside the proxy, and that exception is unhandled -> the proxy returns a bare HTTP
    // 500 with no JSON body for every single chat request. Flatten content back into a plain
    // string here, after serialization, so only AI Horde's wire shape changes.
    override fun postProcessRequestJson(requestJson: String): String {
        return try {
            val root = Json.parseToJsonElement(requestJson) as? JsonObject ?: return requestJson
            val messages = root["messages"] as? JsonArray ?: return requestJson
            val flattenedMessages = JsonArray(messages.map(::flattenMessageContent))
            val mutable = LinkedHashMap(root)
            mutable["messages"] = flattenedMessages
            JsonObject(mutable).toString()
        } catch (e: Exception) {
            requestJson
        }
    }

    private fun flattenMessageContent(message: JsonElement): JsonElement {
        val obj = message as? JsonObject ?: return message
        val parts = obj["content"] as? JsonArray ?: return message
        val text = parts.mapNotNull { part ->
            val partObj = part as? JsonObject ?: return@mapNotNull null
            val type = (partObj["type"] as? JsonPrimitive)?.contentOrNull
            if (type == "text") (partObj["text"] as? JsonPrimitive)?.contentOrNull else null
        }.joinToString("\n\n")
        val mutable = LinkedHashMap(obj)
        mutable["content"] = JsonPrimitive(text)
        return JsonObject(mutable)
    }

    // fetchModels() is overridden only because the proxy's model catalog needs response-shape
    // tolerance (some deployments answer with a bare JSON array, others with the standard OpenAI
    // {"data": [...]} envelope) — NOT because the path itself differs from generateResponse()'s.
    // Both must resolve against the SAME effectiveBaseUrl (which already ends in "/v1", see above),
    // so this appends only the relative "models" segment, matching how generateResponse() appends
    // "chat/completions" — never hardcode "/v1/" again here without also fixing generateResponse().
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) {
            val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
            val url = "$effectiveBaseUrl/models"
            val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
            val responseBody = HttpClient.fetchModelsResponse(url, headers).requireModelFetchBody()
            val entries: List<JsonObject> = decodeModelFetchResponse {
                when (val root = Json.parseToJsonElement(responseBody)) {
                    is JsonArray -> root.filterIsInstance<JsonObject>()
                    is JsonObject -> (root["data"] as? JsonArray)
                        ?.filterIsInstance<JsonObject>()
                        .orEmpty()
                    else -> emptyList()
                }
            }
            val ids = entries.mapNotNull { obj ->
                (obj["id"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
            }.distinct().sorted()
            if (ids.isEmpty()) throw ModelFetchEmptyResultException()
            ids
        }
}
