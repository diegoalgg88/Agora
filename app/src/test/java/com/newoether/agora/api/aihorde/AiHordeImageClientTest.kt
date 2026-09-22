package com.newoether.agora.api.aihorde

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavior tests for the AI Horde async v2 image flow. Asserts the submit/poll/download
 * contract and the distinct terminal outcomes (success, faulted, censored, timeout) —
 * not implementation spelling. HttpClient is mocked as an object (project pattern:
 * ProviderRetryRequestResolutionTest).
 */
class AiHordeImageClientTest {

    @Before
    fun setUp() {
        mockkObject(DebugLog)
        every { DebugLog.d(any(), any(), any()) } returns Unit
        every { DebugLog.e(any(), any(), any()) } returns Unit
        every { DebugLog.w(any(), any(), any()) } returns Unit
        mockkObject(HttpClient)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `generate succeeds after queued then processing polls`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"id":"job-1"}""")
        every { HttpClient.getTextResponse(match { it.endsWith("/check/job-1") }, any()) } returnsMany listOf(
            ok("""{"done":false,"faulted":false}"""),
            ok("""{"done":false,"faulted":false}"""),
            ok("""{"done":true,"faulted":false}"""),
        )
        every { HttpClient.getTextResponse(match { it.endsWith("/status/job-1") }, any()) } returns ok(
            """{"generations":[{"img":"https://r2.example/img.webp","model":"stable_diffusion"}]}""",
        )
        val bytes = byteArrayOf(1, 2, 3)
        every { HttpClient.getBytes(any(), any(), any(), any()) } returns bytes
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat",
            apiKey = "",
            model = "stable_diffusion",
            width = 512,
            height = 512,
            timeoutMs = 10_000,
            pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Success)
        val success = outcome as AiHordeImageClient.GenerationOutcome.Success
        assertEquals(bytes, success.bytes)
        assertEquals("stable_diffusion", success.model)
        // Anonymous key must be used when none is provided.
        verify { HttpClient.postTextResponse(any(), any(), any(), any()) }
    }

    @Test
    fun `generate reports faulted terminal state as failure`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"id":"job-2"}""")
        every { HttpClient.getTextResponse(any(), any()) } returns ok("""{"done":false,"faulted":true}""")
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 10_000, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Failure)
        assertEquals("faulted", (outcome as AiHordeImageClient.GenerationOutcome.Failure).code)
    }

    @Test
    fun `generate surfaces censored generation from final status`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"id":"job-4"}""")
        every { HttpClient.getTextResponse(match { it.endsWith("/check/job-4") }, any()) } returns
            ok("""{"done":true,"faulted":false}""")
        every { HttpClient.getTextResponse(match { it.endsWith("/status/job-4") }, any()) } returns ok(
            """{"generations":[{"censored":true,"censored_message":"csam filter"}]}""",
        )
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 10_000, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Censored)
        assertEquals("csam filter", (outcome as AiHordeImageClient.GenerationOutcome.Censored).reason)
    }

    @Test
    fun `generate fails with no_job_id when submit payload cannot be parsed`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"message":"malformed"}""")
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 10_000, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Failure)
        assertEquals("no_job_id", (outcome as AiHordeImageClient.GenerationOutcome.Failure).code)
    }

    @Test
    fun `generate fails with submit_failed when submit response is not successful`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns HttpClient.TextResponse(code = 500, body = "", isSuccessful = false)
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 10_000, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Failure)
        assertEquals("submit_failed", (outcome as AiHordeImageClient.GenerationOutcome.Failure).code)
    }

    @Test
    fun `generate times out when queue never finishes`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"id":"job-5"}""")
        every { HttpClient.getTextResponse(any(), any()) } returns ok("""{"done":false,"faulted":false}""")
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 5, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Failure)
        assertEquals("timeout", (outcome as AiHordeImageClient.GenerationOutcome.Failure).code)
    }

    @Test
    fun `generate keeps polling after a transient check error then succeeds`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.postTextResponse(any(), any(), any(), any()) } returns ok("""{"id":"job-6"}""")
        every { HttpClient.getTextResponse(match { it.endsWith("/check/job-6") }, any()) } returnsMany listOf(
            ok("""{"done":false,"faulted":false}"""),
            // Corrupt body one poll — must not terminate the wait loop.
            ok("not-json"),
            ok("""{"done":true,"faulted":false}"""),
        )
        every { HttpClient.getTextResponse(match { it.endsWith("/status/job-6") }, any()) } returns ok(
            """{"generations":[{"img":"https://r2.example/x.webp","model":"flux"}]}""",
        )
        every { HttpClient.getBytes(any(), any(), any(), any()) } returns byteArrayOf(9)
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val outcome = AiHordeImageClient.generate(
            prompt = "a cat", apiKey = "key", model = "", width = 512, height = 512,
            timeoutMs = 10_000, pollIntervalMs = 1,
        )

        assertTrue(outcome is AiHordeImageClient.GenerationOutcome.Success)
    }

    @Test
    fun `fetchImageModelNames filters to image models only`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.getTextResponse(any(), any()) } returns ok(
            """[
                {"name":"stable_diffusion","type":"image","count":3},
                {"name":"llama","type":"text","count":2},
                {"name":"","type":"image","count":1},
                {"name":"flux","type":"image","count":5}
            ]""",
        )
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val names = AiHordeImageClient.fetchImageModelNames("")

        assertEquals(listOf("stable_diffusion", "flux"), names)
    }

    @Test
    fun `fetchImageModelNames returns empty on http failure`() = runTest {
        fun ok(body: String) = HttpClient.TextResponse(code = 200, body = body, isSuccessful = true)
        every { HttpClient.getTextResponse(any(), any()) } returns
            HttpClient.TextResponse(code = 500, body = "", isSuccessful = false)
        every { HttpClient.guardCleartextCredentials(any(), any()) } returns Unit

        val names = AiHordeImageClient.fetchImageModelNames("")

        assertTrue(names.isEmpty())
    }
}
