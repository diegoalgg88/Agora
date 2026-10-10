package com.newoether.agora.api.gemini

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import com.sun.net.httpserver.HttpServer
import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Regression for the Gemini 400 INVALID_ARGUMENT
 * `function_declarations[N].properties[polygon].items.items: missing field` reported when
 * bridging MCP tools whose schemas contain arrays of arrays (TomTom polygon / boundingBox /
 * locations). The request serializer must recurse into nested `items`, and an `array` declared
 * without any element schema must never reach the wire bare.
 */
class GeminiToolSchemaSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun nestedArrayItemsSurviveWireSerialization() {
        val captured = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val response = ("data: {\"candidates\":[{\"content\":{\"role\":\"model\"," +
                "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val tools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        name = "tomtom_area_search",
                        description = "Find POIs inside a polygon",
                        parameters = ToolParameters(
                            properties = linkedMapOf(
                                // Array of arrays: items.items must exist on the wire.
                                "polygon" to ToolProperty(
                                    type = "array",
                                    description = "Polygon vertices",
                                    items = ToolProperty(
                                        type = "array",
                                        description = "One [lon, lat] pair",
                                        items = ToolProperty(
                                            type = "number",
                                            description = "Coordinate value",
                                        ),
                                    ),
                                ),
                                // Array declared without element schema: gets a permissive
                                // string element instead of failing the whole request.
                                "tags" to ToolProperty(
                                    type = "array",
                                    description = "Optional tag filters",
                                ),
                                "limit" to ToolProperty(type = "integer", description = "Max results"),
                            ),
                        ),
                    ),
                ),
            )
            val events = runBlocking {
                withTimeout(5_000L) {
                    GeminiProvider().generateResponse(
                        messages = listOf(ChatMessage(text = "search", participant = Participant.USER)),
                        config = ProviderConfig(
                            apiKey = "test-key",
                            modelId = "gemini-3.1-flash-lite",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                            tools = tools,
                        ),
                    ).toList()
                }
            }

            assertTrue(events.none { it is StreamEvent.Error })
            val body = Json.parseToJsonElement(
                checkNotNull(captured.poll(1, TimeUnit.SECONDS)),
            ).jsonObject
            val polygon = body.getValue("tools").jsonArray
                .single().jsonObject.getValue("function_declarations").jsonArray
                .single().jsonObject.getValue("parameters").jsonObject
                .getValue("properties").jsonObject.getValue("polygon").jsonObject
            // Outer items must be an array whose own items exist (the reported missing field).
            val outerItems = polygon.getValue("items").jsonObject
            assertEquals("array", outerItems.getValue("type").jsonPrimitive.content)
            // properties[polygon].items.items — the exact field the 400 complained about.
            val innerItems = outerItems.getValue("items").jsonObject
            assertEquals("number", innerItems.getValue("type").jsonPrimitive.content)
            // Array without element schema never reaches the wire bare.
            val tags = body.getValue("tools").jsonArray
                .single().jsonObject.getValue("function_declarations").jsonArray
                .single().jsonObject.getValue("parameters").jsonObject
                .getValue("properties").jsonObject.getValue("tags").jsonObject
            assertEquals("array", tags.getValue("type").jsonPrimitive.content)
            assertEquals(
                "string",
                tags.getValue("items").jsonObject.getValue("type").jsonPrimitive.content,
            )
        } finally {
            server.stop(0)
        }
    }
}
