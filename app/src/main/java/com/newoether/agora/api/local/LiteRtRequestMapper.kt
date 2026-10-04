package com.newoether.agora.api.local

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.util.buildToolCallId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure mapping from Agora's [ChatMessage] history plus [ProviderConfig] into the LiteRT-LM SDK
 * conversation shape. The SDK owns prompt rendering via the bundle's embedded template, so
 * this mapper never renders or overrides templates — a bundle without a usable template simply
 * fails at conversation use, fail-closed like the GGUF path.
 */
internal object LiteRtRequestMapper {
    private val schemaJson = Json { encodeDefaults = false; explicitNulls = false }
    private val gson = Gson()

    /**
     * Result of mapping one generation request: the ConversationConfig to apply at creation
     * time plus the per-send message and sampling values.
     */
    internal class MappedRequest(
        val conversationConfig: ConversationConfig,
        val sendMessage: Message,
        val repetitionPenaltyConfig: RepetitionPenaltyConfig?,
        val maxOutputToken: Int?,
        val thinkingConfig: ThinkingConfig?,
    )

    fun map(
        resolvedMessages: List<ChatMessage>,
        systemPrompt: String?,
        modelConfig: LocalChatModelConfig,
        config: ProviderConfig,
    ): MappedRequest {
        val hasImages = resolvedMessages.any { message ->
            message.participant == Participant.USER && message.images.any(String::isNotBlank)
        }

        // History = every message except the final one; the final one is what we send now.
        val history = resolvedMessages.dropLast(1)
        val last = resolvedMessages.last()

        val initialMessages = history.mapNotNull(::messageFor)
        val sendContents = contentsFor(last, allowImages = modelConfig.visionCapable)
        val sendMessage = when (last.participant) {
            Participant.USER -> Message.user(sendContents)
            else -> {
                // Continuation pass: the last message carries tool results for the model.
                val toolResponses = toolResponsesFor(last)
                if (toolResponses.isNotEmpty()) {
                    Message.tool(Contents.of(*toolResponses.toTypedArray()))
                } else {
                    Message.user(sendContents)
                }
            }
        }

        val tools = config.tools.orEmpty().map(::toolFor)
        val sampler = SamplerConfig(
            topK = modelConfig.topK.coerceAtLeast(1),
            topP = (config.topP ?: modelConfig.topP).toDouble(),
            temperature = (config.temperature ?: modelConfig.temperature).toDouble(),
        )
        val maxOutputToken = (config.maxTokens ?: modelConfig.maxTokens).takeIf { it > 0 }
        val thinkingConfig = ThinkingConfig(
            enableThinking = config.thinkingEnabled,
            thinkingTokenBudget = if (config.thinkingBudgetEnabled) {
                config.thinkingBudgetTokens
            } else {
                -1
            },
        )
        val penalties = RepetitionPenaltyConfig(
            presencePenalty = (config.presencePenalty ?: 0f),
            frequencyPenalty = (config.frequencyPenalty ?: 0f),
        )

        val conversationConfig = ConversationConfig(
            systemInstruction = systemPrompt?.takeIf(String::isNotBlank)?.let(Contents::of),
            initialMessages = initialMessages,
            tools = tools,
            automaticToolCalling = false,
            samplerConfig = sampler,
            maxOutputToken = maxOutputToken,
            thinkingConfig = thinkingConfig,
            // MTP unlock (UI-ready): pass `enableSpeculativeDecoding = modelConfig.mtp` here
            // once the SDK stable that includes upstream bc16765a ships —
            // ConversationConfig gained per-conversation speculative decoding after 0.17.1,
            // with lazy drafter init and no process-global toggle. The per-model `mtp`
            // field, UI toggles, and persistence are already wired end-to-end.
        )

        return MappedRequest(
            conversationConfig = conversationConfig,
            sendMessage = sendMessage,
            repetitionPenaltyConfig = penalties,
            maxOutputToken = maxOutputToken,
            thinkingConfig = thinkingConfig,
        )
    }

    private fun toolFor(definition: ToolDefinition): com.google.ai.edge.litertlm.ToolProvider =
        tool(AgoraOpenApiTool(definition))

    private class AgoraOpenApiTool(private val definition: ToolDefinition) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            val function = definition.function
            val schema = schemaJson.encodeToString(function.parameters)
            val parameters = JsonParser.parseString(schema).asJsonObject
            return gson.toJson(
                mapOf(
                    "name" to function.name,
                    "description" to function.description,
                    "parameters" to parameters,
                )
            )
        }

        /**
         * Never called: automatic tool calling is disabled, so the SDK only forwards the tool
         * description and Agora executes tools through its own pipeline.
         */
        override fun execute(paramsJsonString: String): String =
            throw IllegalStateException("Agora executes tools outside the LiteRT-LM SDK")
    }

    private fun messageFor(message: ChatMessage): Message? = when {
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> assistantToolCallMessage(message)
        message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
            val responses = toolResponsesFor(message)
            if (responses.isEmpty()) null else Message.tool(Contents.of(*responses.toTypedArray()))
        }
        else -> when (message.participant) {
            Participant.USER -> Message.user(contentsFor(message, allowImages = false))
            Participant.MODEL -> Message.model(contentsFor(message, allowImages = false))
            Participant.ERROR -> null
        }
    }

    private fun assistantToolCallMessage(message: ChatMessage): Message? {
        val toolSegs = message.segments?.filter { it.type == "tool" }
        val calls = if (!toolSegs.isNullOrEmpty()) {
            toolSegs.map { seg ->
                val name = seg.toolName.orEmpty()
                val arguments = seg.toolArgs ?: "{}"
                ToolCall(
                    name = name,
                    arguments = parseArguments(arguments),
                )
            }
        } else {
            message.toolCall?.let { call ->
                listOf(
                    ToolCall(
                        name = call.toolName,
                        arguments = parseArguments(call.arguments),
                    )
                )
            }.orEmpty()
        }
        if (calls.isEmpty()) return null
        return Message.model(Contents.of(""), calls)
    }

    private fun toolResponsesFor(message: ChatMessage): List<Content.ToolResponse> {
        val toolSegs = message.segments?.filter { it.type == "tool" }
        return if (!toolSegs.isNullOrEmpty()) {
            toolSegs.map { seg ->
                Content.ToolResponse(
                    name = seg.toolName.orEmpty(),
                    response = seg.toolResult.orEmpty(),
                )
            }
        } else {
            message.toolCall?.let { call ->
                listOf(
                    Content.ToolResponse(
                        name = call.toolName,
                        response = call.result,
                    )
                )
            }.orEmpty()
        }
    }

    private fun parseArguments(arguments: String): Map<String, Any> = try {
        JsonParser.parseString(arguments).asJsonObject.let { obj ->
            obj.keySet().associateWith { key -> gson.fromJson(obj.get(key), Any::class.java) }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun contentsFor(message: ChatMessage, allowImages: Boolean): Contents {
        val parts = mutableListOf<Content>()
        if (allowImages) {
            message.images.filter(String::isNotBlank).forEach { path ->
                parts.add(Content.ImageFile(path))
            }
        }
        if (message.text.isNotBlank()) {
            parts.add(Content.Text(message.text))
        }
        if (parts.isEmpty()) {
            return Contents.of("")
        }
        return Contents.of(*parts.toTypedArray())
    }
}
