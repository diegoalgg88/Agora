package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRoundEffectCoordinatorTest {
    @Before
    fun quietLogs() {
        // DebugLog delega en android.util.Log, no mockeado en JVM tests: apagar el flag
        // privado evita "Method w not mocked" sin tocar la config compartida del build.
        val field = DebugLog::class.java.getDeclaredField("enabled")
        field.isAccessible = true
        field.setBoolean(DebugLog, false)
    }
    @Test
    fun `effect order is batch then results then durable commit then continuation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ToolRoundEffectCoordinator(callbacks(events))

        val batch = checkNotNull(coordinator.acceptValidatedBatch(PROVIDER_IDENTITY))
        val commit = checkNotNull(coordinator.completeBatch(batch.identity))
        val continuation = checkNotNull(coordinator.commitRound { identity ->
            assertEquals(commit.identity, identity)
            events += "persist"
        })

        assertEquals(commit.identity, continuation.identity)
        assertEquals(
            listOf("request", "complete", "persist", "settled:true"),
            events,
        )
    }

    @Test
    fun `durable failure reports failure and never authorizes continuation`() = runTest {
        val events = mutableListOf<String>()
        val coordinator = ToolRoundEffectCoordinator(callbacks(events))
        val batch = checkNotNull(coordinator.acceptValidatedBatch(PROVIDER_IDENTITY))
        coordinator.completeBatch(batch.identity)

        val failure = runCatching {
            coordinator.commitRound {
                events += "persist"
                error("database failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf("request", "complete", "persist", "settled:false"),
            events,
        )
    }

    @Test
    fun `runtime rejection prevents tool execution or continuation`() = runTest {
        val callbacks = callbacks(mutableListOf()).copy(
            onToolBatchRequested = { null },
        )
        val coordinator = ToolRoundEffectCoordinator(callbacks)

        // El rechazo viaja como null (sin CancellationException manual — SIGABRT 2026-10-26).
        org.junit.Assert.assertNull(coordinator.acceptValidatedBatch(PROVIDER_IDENTITY))
    }

    @Test
    fun `runtime rejection at completion and continuation also degrades to null`() = runTest {
        // completeBatch rechazado: el batch queda sin cerrar y retorna null, sin throw.
        val completing = callbacks(mutableListOf()).copy(
            onToolBatchCompleted = { null },
        )
        val coordinator = ToolRoundEffectCoordinator(completing)
        val batch = checkNotNull(coordinator.acceptValidatedBatch(PROVIDER_IDENTITY))
        org.junit.Assert.assertNull(coordinator.completeBatch(batch.identity))

        // commitRound con continuación que no es current: null tras el persist durable.
        val continuing = callbacks(mutableListOf()).copy(
            onToolRoundCommitted = { identity, _ ->
                RunEffect.ToolRoundCommitFailed(identity)
            },
        )
        val second = ToolRoundEffectCoordinator(continuing)
        val batch2 = checkNotNull(second.acceptValidatedBatch(PROVIDER_IDENTITY))
        second.completeBatch(batch2.identity)
        org.junit.Assert.assertNull(second.commitRound { })
    }

    @Test
    fun `wrong callback identity fails closed`() = runTest {
        val callbacks = callbacks(mutableListOf()).copy(
            onToolBatchRequested = { identity ->
                RunEffect.ExecuteToolBatch(identity.copy(effectId = "wrong"))
            },
        )
        val coordinator = ToolRoundEffectCoordinator(callbacks)

        val failure = runCatching {
            coordinator.acceptValidatedBatch(PROVIDER_IDENTITY)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun callbacks(events: MutableList<String>) = GenerationCallbacks(
        onStreamUpdate = { _: ChatMessage -> },
        onLoadingChange = {},
        onStreamClear = {},
        isLatestPersist = { true },
        onProviderPassRequested = { RunEffect.StartProviderPass(it) },
        onProviderPassCompleted = { identity, result ->
            RunEffect.ProviderPassAccepted(identity, result)
        },
        onRunFinalizationRequested = { identity, status, reason, markUnread ->
            RunEffect.FinalizeRun(identity, status, reason, markUnread)
        },
        onRunFinalizationCompleted = { _, _ -> true },
        onToolBatchRequested = { identity ->
            events += "request"
            RunEffect.ExecuteToolBatch(
                identity.copy(effectId = "tool-batch-${identity.effectId}"),
            )
        },
        onToolBatchCompleted = { identity ->
            events += "complete"
            RunEffect.CommitToolRound(
                identity.copy(effectId = "tool-round-${identity.effectId}"),
            )
        },
        onToolRoundCommitted = { identity, success ->
            events += "settled:$success"
            if (success) RunEffect.ContinueProviderPass(identity)
            else RunEffect.ToolRoundCommitFailed(identity)
        },
    )

    private companion object {
        val PROVIDER_IDENTITY = RunEffectIdentity(
            conversationId = "conversation",
            ownerToken = 1,
            runId = "run",
            pass = 2,
            effectId = "provider-2-0",
        )
    }
}
