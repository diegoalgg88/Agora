package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.aihorde.AiHordeImageClient
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tool that generates images through an OpenAI-compatible `/images/generations` endpoint.
 * Successful bytes are persisted by [ToolImageStore] and returned on the owning tool result.
 */
class ImageGenToolProvider(private val app: Application) : ToolProvider {

    private val imageStore = ToolImageStore(app)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.imageGenEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "generate_image",
                description = "Generate an image from a text prompt. The generated image is shown to the user automatically — do NOT attempt to embed or describe the raw image data. Use this whenever the user asks to create, draw, paint, or generate a picture.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "prompt" to ToolProperty("string", "A detailed description of the image to generate."),
                        "size" to ToolProperty("string", "Optional image size, e.g. 1024x1024, 1024x1536, or 1536x1024.")
                    ),
                    required = listOf("prompt")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name == "generate_image"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = executeResult(name, arguments, ctx).text

    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        emit(ToolExecutionEvent.Completed(executeResult(name, arguments, ctx)))
    }

    private suspend fun executeResult(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val textAndImage = executeOffMain(name, arguments, ctx)
        ToolExecutionResult(
            text = textAndImage.first,
            images = listOfNotNull(textAndImage.second),
            isError = textAndImage.second == null,
        )
    }

    private suspend fun executeOffMain(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Pair<String, com.newoether.agora.model.ToolImageAttachment?> {
        ctx.conversationId?.takeIf { it.isNotBlank() }
            ?: return err("missing_conversation", "Image generation requires a conversation.") to null
        val argsStr = arguments.ifBlank { "{}" }
        val args = try {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        } catch (_: Exception) { emptyMap() }
        val prompt = (args["prompt"] as? JsonPrimitive)?.content
            ?: return err("no_prompt", null) to null
        val size = (args["size"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: ctx.imageGenSize.ifBlank { "1024x1024" }

        val apiKey = ctx.imageGenApiKey
        // AI Horde branch: native async v2 API (submit → poll → download). Same tool name and
        // owner, so the 600s timeout override keyed on "generate_image" keeps applying.
        if (ctx.imageGenBackend == "ai_horde") {
            return executeAiHorde(prompt, size, ctx)
        }
        if (apiKey.isBlank()) return err("no_api_key", null) to null
        val baseUrl = ctx.imageGenBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL }.trimEnd('/')
        val model = ctx.imageGenModel.ifBlank { "gpt-image-1" }

        return withContext(Dispatchers.IO) {
            try {
                val body = buildJsonObject {
                    put("model", model)
                    put("prompt", prompt)
                    put("size", size)
                    put("n", 1)
                }.toString()
                val response = HttpClient.post(
                    "$baseUrl/images/generations",
                    body,
                    mapOf("Authorization" to "Bearer $apiKey"),
                    callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                    readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                ) ?: return@withContext err("no_response", null) to null

                val json = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(response)
                val first = json["data"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@withContext err("no_image", "The endpoint returned no image data.") to null

                val bytes: ByteArray = run {
                    val b64 = (first["b64_json"] as? JsonPrimitive)?.content
                    if (!b64.isNullOrBlank()) {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } else {
                        val url = (first["url"] as? JsonPrimitive)?.content
                            ?: return@withContext err(
                                "no_image",
                                "No b64_json or url in the response.",
                            ) to null
                        HttpClient.getBytes(
                            url,
                            callTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                            readTimeoutMillis = Constants.IMAGE_GENERATION_TIMEOUT_MS,
                        ) ?: return@withContext err("download_failed", null) to null
                    }
                }

                val attachment = imageStore.persistGeneratedBytes(
                    bytes = bytes,
                    filePrefix = "generated_image",
                )
                buildJsonObject {
                    put("type", "image_generation")
                    put("status", "ok")
                    put("size", size)
                }.toString() to attachment
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("ImageGenTool", "generate_image failed", e)
                err("generation_error", e.message) to null
            }
        }
    }

    /**
     * AI Horde branch of [executeOffMain]. Runs the native async v2 flow inside the tool's
     * IO dispatcher — never on the Provider stream (invariant #6). A censored image is
     * reported as such instead of being retried as a technical failure.
     */
    private suspend fun executeAiHorde(
        prompt: String,
        size: String,
        ctx: GenerationContext,
    ): Pair<String, com.newoether.agora.model.ToolImageAttachment?> {
        val dims = size.split("x").mapNotNull { it.trim().toIntOrNull() }
        val width = dims.getOrNull(0)?.coerceIn(64, 3072) ?: 1024
        val height = dims.getOrNull(1)?.coerceIn(64, 3072) ?: 1024

        return when (val outcome = AiHordeImageClient.generate(
            prompt = prompt,
            apiKey = ctx.aiHordeApiKey,
            model = ctx.aiHordeImageModel,
            width = width,
            height = height,
            timeoutMs = Constants.IMAGE_GENERATION_TIMEOUT_MS,
        )) {
            is AiHordeImageClient.GenerationOutcome.Success -> {
                val attachment = imageStore.persistGeneratedBytes(
                    bytes = outcome.bytes,
                    filePrefix = "generated_image",
                )
                buildJsonObject {
                    put("type", "image_generation")
                    put("status", "ok")
                    put("backend", "ai_horde")
                    put("size", "${width}x${height}")
                    if (outcome.model.isNotBlank()) put("model", outcome.model)
                }.toString() to attachment
            }
            is AiHordeImageClient.GenerationOutcome.Censored ->
                err("censored", outcome.reason) to null
            is AiHordeImageClient.GenerationOutcome.Failure ->
                err(outcome.code, outcome.message) to null
        }
    }

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "image_generation")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
