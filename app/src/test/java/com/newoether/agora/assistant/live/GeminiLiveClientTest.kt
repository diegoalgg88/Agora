package com.newoether.agora.assistant.live

import com.newoether.agora.util.DebugLog
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Socket-identity behavior of [GeminiLiveClient] (the reconnect race, N1): every connect()
 * starts a generation and every close() invalidates it, so a late callback from the socket a
 * reconnect just replaced — onClosed, onFailure or a straggler frame — must never be mistaken
 * for the health of the new connection (no phantom Disconnected, no watchdog cancellation on
 * the live socket, no cross-socket content bleed).
 */
class GeminiLiveClientTest {

    private class RecordingFactory : WebSocket.Factory {
        val listeners = mutableListOf<WebSocketListener>()
        val sockets = mutableListOf<WebSocket>()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = mockk<WebSocket>(relaxed = true)
            // send() has two overloads (String / ByteString) — pin the matcher to the text one.
            every { socket.send(any<String>()) } returns true
            sockets += socket
            listeners += listener
            return socket
        }
    }

    private lateinit var factory: RecordingFactory
    private val events = mutableListOf<LiveConnectionEvent>()
    private val contents = mutableListOf<LiveServerContent>()
    private lateinit var client: GeminiLiveClient

    @Before
    fun setUp() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any()) } just Runs
        every { DebugLog.w(any(), any()) } just Runs
        every { DebugLog.w(any(), any(), any()) } just Runs
        factory = RecordingFactory()
        events.clear()
        contents.clear()
        client = GeminiLiveClient(
            apiKey = "test-key",
            events = { events += it },
            onContent = { contents += it },
            webSocketFactory = factory,
        )
    }

    @After
    fun tearDown() {
        unmockkObject(DebugLog)
    }

    private fun openSocket(index: Int) {
        factory.listeners[index].onOpen(factory.sockets[index], syntheticResponse())
    }

    private fun deliverSetupComplete(index: Int) {
        factory.listeners[index].onMessage(
            factory.sockets[index],
            """{"setupComplete":{}}""".encodeUtf8(),
        )
    }

    private fun syntheticResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://generativelanguage.test").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private fun disconnectedEvents(): List<LiveConnectionEvent.Disconnected> =
        events.filterIsInstance<LiveConnectionEvent.Disconnected>()

    @Test
    fun `late onClosed and onFailure from the replaced socket are ignored`() {
        client.connect("gemini-3.1-flash-live-preview", "Kore")
        openSocket(0)
        deliverSetupComplete(0)
        assertEquals(listOf(LiveConnectionEvent.Ready), events)

        // Reconnect path: close the old connection, connect again with the resumption handle.
        client.close()
        client.connect("gemini-3.1-flash-live-preview", "Kore", resumptionHandle = "h2")
        openSocket(1)
        deliverSetupComplete(1)
        events.removeAt(1) // the second Ready; asserted indirectly by size below

        // The OLD socket now reports its close/failure after the new one is already healthy.
        factory.listeners[0].onClosed(factory.sockets[0], 1001, "server restarted")
        factory.listeners[0].onFailure(factory.sockets[0], RuntimeException("stale"), null)

        // No phantom Disconnected for the new connection, no duplicate Ready loss: the event
        // list still holds exactly the first Ready (the second was removed above) plus nothing.
        assertEquals(listOf(LiveConnectionEvent.Ready), events)
        assertTrue(disconnectedEvents().isEmpty())
    }

    @Test
    fun `frames from the replaced socket are not dispatched onto the new session`() {
        client.connect("gemini-3.1-flash-live-preview", "Kore")
        openSocket(0)
        deliverSetupComplete(0)
        client.close()
        client.connect("gemini-3.1-flash-live-preview", "Kore", resumptionHandle = "h2")
        openSocket(1)
        deliverSetupComplete(1)

        val staleFrame: ByteString =
            """{"serverContent":{"inputTranscription":{"text":"old mic bleed"}}}""".encodeUtf8()
        factory.listeners[0].onMessage(factory.sockets[0], staleFrame)

        assertTrue(contents.isEmpty())
    }

    @Test
    fun `only the current socket's close emits Disconnected`() {
        client.connect("gemini-3.1-flash-live-preview", "Kore")
        openSocket(0)
        deliverSetupComplete(0)
        client.close()
        client.connect("gemini-3.1-flash-live-preview", "Kore", resumptionHandle = "h2")
        openSocket(1)
        deliverSetupComplete(1)

        // Stale close first, then the real one.
        factory.listeners[0].onClosed(factory.sockets[0], 1001, "stale")
        factory.listeners[1].onClosed(factory.sockets[1], 1000, "gone")

        assertEquals(1, disconnectedEvents().size)
        assertEquals("closed code=1000", disconnectedEvents().single().cause)
    }

    @Test
    fun `setupComplete carries the resumption handle update and goAway parses`() {
        client.connect("gemini-3.1-flash-live-preview", "Kore")
        openSocket(0)
        deliverSetupComplete(0)
        factory.listeners[0].onMessage(
            factory.sockets[0],
            """{"sessionResumptionUpdate":{"newHandle":"h9","resumable":true}}""".encodeUtf8(),
        )
        assertEquals("h9", client.lastResumptionHandle)

        factory.listeners[0].onMessage(
            factory.sockets[0],
            """{"goAway":{"timeLeft":"30s"}}""".encodeUtf8(),
        )
        val goingAway = events.filterIsInstance<LiveConnectionEvent.ServerGoingAway>().single()
        assertEquals(30L, goingAway.timeLeftSeconds)
    }
}
