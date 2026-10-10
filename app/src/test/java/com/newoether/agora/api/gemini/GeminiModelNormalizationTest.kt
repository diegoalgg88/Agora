package com.newoether.agora.api.gemini

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Regression for the doubled wire model name observed when a manually entered custom model ID
 * carries a capitalized "Models/" prefix: the request URL became
 * "models/Models/gemini-3.1-flash-live-preview:streamGenerateContent" and the API answered
 * 400 INVALID_ARGUMENT (it resolved the model and rejected the generateContent family because
 * live models are bidi-WebSocket-only). The provider must normalize the prefix
 * case-insensitively, and the model catalog must only surface generateContent-capable models.
 */
class GeminiModelNormalizationTest {

    @Before
    fun disableAndroidLoggingForJvmNetworkTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    @Test
    fun capitalModelsPrefixDoesNotDoubleInTheRequestPath() {
        val paths = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths.add(exchange.requestURI.path)
            exchange.requestBody.use { it.readBytes() }
            val response = ("data: {\"candidates\":[{\"content\":{\"role\":\"model\"," +
                "\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val events = runBlocking {
                withTimeout(5_000L) {
                    GeminiProvider().generateResponse(
                        messages = listOf(ChatMessage(text = "hi", participant = Participant.USER)),
                        config = ProviderConfig(
                            apiKey = "test-key",
                            modelId = "Models/gemini-3.1-flash-lite",
                            baseUrl = "http://127.0.0.1:${server.address.port}",
                        ),
                    ).toList()
                }
            }

            assertTrue(events.none { it is StreamEvent.Error })
            assertEquals(
                "/v1beta/models/gemini-3.1-flash-lite:streamGenerateContent",
                paths.poll(1, TimeUnit.SECONDS),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun modelCatalogOnlySurfacesGenerateContentCapableModels() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = """
            {
              "models": [
                {"name": "models/gemini-3.1-flash-lite",
                 "displayName": "Gemini 3.1 Flash-Lite",
                 "supportedGenerationMethods": ["generateContent", "countTokens"]},
                {"name": "models/gemini-3.1-flash-live-preview",
                 "displayName": "Gemini 3.1 Flash Live",
                 "supportedGenerationMethods": ["bidiGenerateContent"]},
                {"name": "models/embedding-001",
                 "displayName": "Embedding",
                 "supportedGenerationMethods": ["embedContent", "countTokens"]}
              ]
            }
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val models = runBlocking {
                GeminiProvider().fetchModels(
                    apiKey = "test-key",
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                )
            }

            assertEquals(listOf("gemini-3.1-flash-lite"), models)
        } finally {
            server.stop(0)
        }
    }
}
