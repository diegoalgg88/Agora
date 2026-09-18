package com.newoether.agora.assistant.live

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the Gemini Live API WebSocket DTOs (plan §10.11): client frames
 * must serialize to the exact wire shape, server frames must decode (including unknown keys),
 * and model ids must normalize. Pure JVM — no socket involved.
 */
class LiveVoiceModelsTest {

    private val json = LiveJson

    // ── Client frames ──────────────────────────────────────────────────────

    @Test
    fun `setup frame serializes with resumption, compression and transcriptions`() {
        val frame = LiveClientFrame(
            setup = LiveSetup(model = "models/gemini-3.1-flash-live-preview"),
        )
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains("\"model\":\"models/gemini-3.1-flash-live-preview\""))
        assertTrue(encoded.contains("\"sessionResumption\":{}"))
        assertTrue(encoded.contains("\"contextWindowCompression\":{\"slidingWindow\":{}}"))
        assertTrue(encoded.contains("\"inputAudioTranscription\":{}"))
        assertTrue(encoded.contains("\"outputAudioTranscription\":{}"))
        assertTrue(encoded.contains("\"responseModalities\":[\"AUDIO\"]"))
        // Exactly one top-level field: setup.
        assertFalse(encoded.contains("\"realtimeInput\""))
    }

    @Test
    fun `setup frame with resumption handle carries it`() {
        val frame = LiveClientFrame(
            setup = LiveSetup(
                model = "models/gemini-3.1-flash-live-preview",
                sessionResumption = LiveSessionResumptionConfig(handle = "abc123"),
            ),
        )
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains("\"sessionResumption\":{\"handle\":\"abc123\"}"))
    }

    @Test
    fun `setup frame omits blank resumption handle`() {
        val frame = LiveClientFrame(
            setup = LiveSetup(
                model = "models/gemini-3.1-flash-live-preview",
                sessionResumption = LiveSessionResumptionConfig(handle = null),
            ),
        )
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains("\"sessionResumption\":{}"))
        assertFalse(encoded.contains("\"handle\""))
    }

    @Test
    fun `audio frame serializes with 16 kHz mime and base64 payload`() {
        val pcm = ByteArray(16) { it.toByte() }
        val b64 = java.util.Base64.getEncoder().encodeToString(pcm)
        val frame = LiveClientFrame(
            realtimeInput = LiveRealtimeInput(
                audio = LiveRealtimeAudio(data = b64),
            ),
        )
        val encoded = json.encodeToString(frame)
        assertTrue(encoded.contains("\"mimeType\":\"audio/pcm;rate=16000\""))
        assertTrue(encoded.contains("\"data\":\"$b64\""))
        assertFalse(encoded.contains("\"setup\""))
    }

    @Test
    fun `audioStreamEnd frame is a bare boolean`() {
        val encoded = json.encodeToString(
            LiveClientFrame(realtimeInput = LiveRealtimeInput(audioStreamEnd = true)),
        )
        assertEquals("""{"realtimeInput":{"audioStreamEnd":true}}""", encoded)
    }

    // ── Server frames ──────────────────────────────────────────────────────

    @Test
    fun `setupComplete decodes`() {
        val frame = json.decodeFromString<LiveServerFrame>("""{"setupComplete":{}}""")
        assertTrue(frame.setupComplete != null)
        assertTrue(frame.serverContent == null)
    }

    @Test
    fun `model turn audio decodes with inline data`() {
        val frame = json.decodeFromString<LiveServerFrame>(
            """
            {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAAA"}}]}}}
            """.trimIndent(),
        )
        val part = frame.serverContent?.modelTurn?.parts?.single()
        assertEquals("audio/pcm;rate=24000", part?.inlineData?.mimeType)
        assertEquals("AAAA", part?.inlineData?.data)
    }

    @Test
    fun `turn lifecycle decodes - interrupted, transcriptions, turnComplete`() {
        val frame = json.decodeFromString<LiveServerFrame>(
            """
            {"serverContent":{
              "interrupted":true,
              "turnComplete":true,
              "inputTranscription":{"text":"hola"},
              "outputTranscription":{"text":"¿qué necesitas?"}
            }}
            """.trimIndent(),
        )
        val content = frame.serverContent!!
        assertEquals(true, content.interrupted)
        assertEquals(true, content.turnComplete)
        assertEquals("hola", content.inputTranscription?.text)
        assertEquals("¿qué necesitas?", content.outputTranscription?.text)
        assertEquals(null, content.modelTurn)
    }

    @Test
    fun `goAway and sessionResumptionUpdate decode`() {
        val goAway = json.decodeFromString<LiveServerFrame>("""{"goAway":{"timeLeft":"30s"}}""")
        assertEquals("30s", goAway.goAway?.timeLeft)

        val update = json.decodeFromString<LiveServerFrame>(
            """{"sessionResumptionUpdate":{"newHandle":"h2","resumable":true}}""",
        )
        assertEquals("h2", update.sessionResumptionUpdate?.newHandle)
        assertEquals(true, update.sessionResumptionUpdate?.resumable)

        val nonResumable = json.decodeFromString<LiveServerFrame>(
            """{"sessionResumptionUpdate":{"newHandle":"","resumable":false}}""",
        )
        assertFalse(nonResumable.sessionResumptionUpdate!!.resumable)
    }

    @Test
    fun `unknown server fields are ignored`() {
        val frame = json.decodeFromString<LiveServerFrame>(
            """{"setupComplete":{},"usageMetadata":{"inputTokenCount":5},"newFeatureX":123}""",
        )
        assertTrue(frame.setupComplete != null)
    }

    // ── Model id normalization ─────────────────────────────────────────────

    @Test
    fun `model ids normalize to models prefix`() {
        assertEquals("models/gemini-3.1-flash-live-preview", normalizeLiveModelId("gemini-3.1-flash-live-preview"))
        assertEquals(
            "models/gemini-3.1-flash-live-preview",
            normalizeLiveModelId("models/gemini-3.1-flash-live-preview"),
        )
        assertEquals("models/ gemini-x", normalizeLiveModelId(" models/ gemini-x"))
    }

    @Test
    fun `blank model id stays blank rather than fabricating a model`() {
        assertEquals("", normalizeLiveModelId("  "))
    }
}
