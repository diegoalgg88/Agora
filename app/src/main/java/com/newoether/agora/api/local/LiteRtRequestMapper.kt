package com.newoether.agora.api.local

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.Role
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
        // A tool round-trip is persisted as ONE result message per call, every one flagged
        // Participant.USER (GenerationToolRoundBuilder). Consecutive results must reach the SDK as a
        // single tool turn carrying every response in call order, and a trailing run of results is
        // the message to send on a continuation pass. Dispatching on participant (the previous
        // behavior) delivered the tool output to the model as plain user text, with the tool call
        // in history left unanswered.
        val sdkMessages = toSdkMessages(
            resolvedMessages,
            allowImagesOnLast = modelConfig.visionCapable,
        )
        val candidate = sdkMessages.lastOrNull() ?: Message.user(Contents.of(""))
        // A model-role tail leaves nothing to answer; resend it as user text (pre-existing
        // behavior for non-user tails).
        val sendMessage = if (candidate.role == Role.MODEL) {
            Message.user(candidate.contents)
        } else {
            candidate
        }
        val initialMessages = sdkMessages.dropLast(1)

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
            // The SDK requires a multiplicative penalty >= 1.0 (init-validated); values below
            // are invalid and treated as "off" (engine default 1.0).
            repetitionPenalty = config.repetitionPenalty?.takeIf { it >= 1.0f },
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
            // MTP is unlocked engine-side: LiteRtChatEngine scopes the SDK's process-global
            // speculative-decoding flag to each engine construction (the flag is read only at
            // Engine.initialize, and this process constructs engines only under the FIFO
            // permit). The SDK's per-conversation API (upstream bc16765a, post-0.17.1) stays
            // unused; a future SDK bump can swap the engine-side scope for
            // `enableSpeculativeDecoding = modelConfig.mtp` here.
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

    /**
     * Maps the resolved path to SDK messages. Runs of consecutive tool-result messages merge
     * into one tool turn; images are only attached to the final message (history images are
     * dropped: the SDK re-encodes every image on each request and the budget is per turn).
     */
    private fun toSdkMessages(
        messages: List<ChatMessage>,
        allowImagesOnLast: Boolean,
    ): List<Message> {
        val out = mutableListOf<Message>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val responses = mutableListOf<Content.ToolResponse>()
                while (index < messages.size &&
                    messages[index].id.startsWith(Constants.RESULT_MSG_PREFIX)
                ) {
                    responses += toolResponsesFor(messages[index])
                    index++
                }
                if (responses.isNotEmpty()) {
                    out += Message.tool(Contents.of(*responses.toTypedArray()))
                }
                continue
            }
            val isLast = index == messages.lastIndex
            messageFor(message, allowImages = isLast && allowImagesOnLast)?.let(out::add)
            index++
        }
        return out
    }

    private fun messageFor(message: ChatMessage, allowImages: Boolean): Message? = when {
        message.id.startsWith(Constants.TOOL_MSG_PREFIX) -> assistantToolCallMessage(message)
        message.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
            val responses = toolResponsesFor(message)
            if (responses.isEmpty()) null else Message.tool(Contents.of(*responses.toTypedArray()))
        }
        else -> when (message.participant) {
            Participant.USER -> Message.user(contentsFor(message, allowImages = allowImages))
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
        // No content part at all: an empty text part renders as an empty assistant string in
        // several chat templates, which then fight the tool_calls block.
        return Message.model(Contents.of(emptyList<Content>()), calls)
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
