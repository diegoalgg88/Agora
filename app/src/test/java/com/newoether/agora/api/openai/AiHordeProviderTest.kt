package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiImageUrl
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AiHordeProvider must resolve against the official OpenAI-compatible proxy base URL so
 * that model sync and chat completions route to oai.aihorde.net without extra config.
 */
class AiHordeProviderTest {

    @Test
    fun `provider is registered under the AI Horde constant name`() {
        val provider = AiHordeProvider()
        assertEquals(Constants.PROVIDER_AI_HORDE, provider.name)
        assertEquals("AI Horde", provider.name)
    }

    @Test
    fun `default base url points to the official horde openai proxy`() {
        assertEquals("https://oai.aihorde.net/v1", AiHordeProvider().defaultBaseUrl)
    }

    // Regression test for the "error 500 en chat" bug: the AI Horde proxy renders messages
    // through a Jinja chat template that requires `content` to be a plain string. Every other
    // provider sends OpenAI's array-of-parts content shape, which crashes that template with an
    // unhandled exception on the proxy side (bare HTTP 500 for every chat request).
    @Test
    fun `flattens array-of-parts message content into plain strings`() {
        val provider = AiHordeProvider()
        val requestJson = Json.encodeToString(
            OpenAiChatRequest.serializer(),
            OpenAiChatRequest(
                model = "koboldcpp/some-model",
                messages = listOf(
                    OpenAiMessage(
                        role = "system",
                        content = listOf(OpenAiContentPart(type = "text", text = "Be helpful.")),
                    ),
                    OpenAiMessage(
                        role = "user",
                        content = listOf(OpenAiContentPart(type = "text", text = "Hello!")),
                    ),
                ),
            ),
        )

        val result = provider.exposedPostProcessRequestJson(requestJson)

        val messages = (Json.parseToJsonElement(result) as JsonObject)["messages"] as JsonArray
        assertEquals("Be helpful.", (messages[0] as JsonObject)["content"]!!.jsonPrimitive.content)
        assertEquals("Hello!", (messages[1] as JsonObject)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `joins multiple text parts and drops image parts when flattening`() {
        val provider = AiHordeProvider()
        val requestJson = Json.encodeToString(
            OpenAiChatRequest.serializer(),
            OpenAiChatRequest(
                model = "koboldcpp/some-model",
                messages = listOf(
                    OpenAiMessage(
                        role = "user",
                        content = listOf(
                            OpenAiContentPart(type = "text", text = "first"),
                            OpenAiContentPart(
                                type = "image_url",
                                imageUrl = OpenAiImageUrl("data:image/png;base64,AA=="),
                            ),
                            OpenAiContentPart(type = "text", text = "second"),
                        ),
                    ),
                ),
            ),
        )

        val result = provider.exposedPostProcessRequestJson(requestJson)

        val messages = (Json.parseToJsonElement(result) as JsonObject)["messages"] as JsonArray
        assertEquals(
            "first\n\nsecond",
            (messages[0] as JsonObject)["content"]!!.jsonPrimitive.content,
        )
    }
}

private fun AiHordeProvider.exposedPostProcessRequestJson(requestJson: String): String {
    val method = AiHordeProvider::class.java.getDeclaredMethod(
        "postProcessRequestJson",
        String::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, requestJson) as String
}
