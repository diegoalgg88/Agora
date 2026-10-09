package com.newoether.agora.automation

import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.util.DebugLog
import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 contract: a successful scheduled-task execution surfaces its result through the rich
 * confirmation pipeline (the same durable staging the heartbeat uses), and a confirmation
 * failure must NEVER flip the completed run into a failure.
 *
 * The surfacing lambda is injected, so these tests pin the TaskManager side: WHEN it fires,
 * WITH what identity, and that its own failure is swallowed. PROMPT/AUTO semantics live in
 * AppContainer.surfaceAutomationResult and mirror the heartbeat tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskManagerResultSurfacingTest {

    /** Focused runs execute before the api-package tests that disable DebugLog globally; the
     *  Failure/surfacing branches log via android.util.Log, which is not mocked on the JVM. */
    @Before
    fun disableAndroidLoggingForJvmTests() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply { flags = 0 }
        DebugLog.forceEnabled = false
        DebugLog.init(context)
    }

    private data class Surfaced(
        val taskId: String,
        val conversationId: String,
        val modelMessageId: String?,
        val response: String,
    )

    // One future slot per test instance: the executeById latency guard drops occurrences that
    // fired far past their slot (System.currentTimeMillis() - nextRunAt >
    // MAX_OCCURRENCE_LATENCY_MS), so a stale 1970-epoch fixture would be Skipped and never
    // reach the engine.
    private val storedNextRunAt: Long = System.currentTimeMillis() + 60_000L

    private fun task(name: String = "Reporte del clima") = TaskEntity(
        id = "task",
        name = name,
        prompt = "Prompt",
        cronExpr = "* * * * *",
        nextRunAt = storedNextRunAt,
        enabled = true,
    )

    @Test
    fun scheduledSuccessSurfacesResultWithExactIdentity() = runTest {
        val surfaced = mutableListOf<Surfaced>()
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Success("model-msg-1", "Lluvia a las 15:00"),
        )
        val manager = TaskManager(
            repository, conversations, engine, backgroundScope,
            surfaceTaskResult = { t, conversationId, modelMessageId, response ->
                surfaced.add(Surfaced(t.id, conversationId, modelMessageId, response))
            },
        )

        val result = manager.executeById("task", "execution", storedNextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Success)
        val s = surfaced.single()
        assertEquals("task", s.taskId)
        assertEquals("model-msg-1", s.modelMessageId)
        assertEquals("Lluvia a las 15:00", s.response)
    }

    @Test
    fun surfacingFailureNeverFlipsTheCompletedRun() = runTest {
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Success("model-msg-1", "ok"),
        )
        val manager = TaskManager(
            repository, conversations, engine, backgroundScope,
            surfaceTaskResult = { _, _, _, _ -> throw RuntimeException("staging exploded") },
        )

        val result = manager.executeById("task", "execution", storedNextRunAt)

        // The task completed; the confirmation pipeline broke on its own and was swallowed.
        assertTrue(result is TaskManager.ExecutionResult.Success)
    }

    @Test
    fun scheduledFailureNeverSurfaces() = runTest {
        val surfaced = mutableListOf<Surfaced>()
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Failure("provider exploded"),
        )
        val manager = TaskManager(
            repository, conversations, engine, backgroundScope,
            surfaceTaskResult = { t, conversationId, modelMessageId, response ->
                surfaced.add(Surfaced(t.id, conversationId, modelMessageId, response))
            },
        )

        val result = manager.executeById("task", "execution", storedNextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Failure)
        assertEquals(0, surfaced.size)
    }

    @Test
    fun defaultLambdaIsInertSoExistingConstructionSitesStayUnchanged() = runTest {
        // No surfaceTaskResult passed: the default must be a no-op, not a crash.
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Success("model-msg-1", "ok"),
        )
        val manager = TaskManager(repository, conversations, engine, backgroundScope)

        val result = manager.executeById("task", "execution", storedNextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Success)
    }

    @Test
    fun confirmationsOnSuppressesTheGenericTerminalNotification() = runTest {
        // One Run, one result notification: with the confirmation toggle on, the rich
        // pipeline is the sole result signal, so the engine must not also post the
        // generic "Agora responded" terminal notification.
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Success("model-msg-1", "ok"),
        )
        val manager = TaskManager(
            repository, conversations, engine, backgroundScope,
            taskConfirmationsEnabled = { true },
        )

        val result = manager.executeById("task", "execution", storedNextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Success)
        coVerify {
            engine.runOnceWithAutomationGuardsHeld(
                any(), any(), any(), any(), any(), any(), any(), true,
            )
        }
    }

    @Test
    fun confirmationsOffKeepsTheGenericTerminalNotification() = runTest {
        // Toggle off: no confirmation pipeline, so the plain terminal notification stays
        // the single result signal.
        val (repository, conversations, engine) = mocksForScheduledRun(
            result = TaskExecutionEngine.Result.Success("model-msg-1", "ok"),
        )
        val manager = TaskManager(
            repository, conversations, engine, backgroundScope,
            taskConfirmationsEnabled = { false },
        )

        val result = manager.executeById("task", "execution", storedNextRunAt)

        assertTrue(result is TaskManager.ExecutionResult.Success)
        coVerify {
            engine.runOnceWithAutomationGuardsHeld(
                any(), any(), any(), any(), any(), any(), any(), false,
            )
        }
    }

    private fun mocksForScheduledRun(
        result: TaskExecutionEngine.Result,
    ): Triple<TaskRepository, ConversationRepository, TaskExecutionEngine> {
        val stored = task()
        val repository = mockk<TaskRepository>()
        val conversations = mockk<ConversationRepository>()
        val engine = mockk<TaskExecutionEngine>()
        every { repository.getAllTasks() } returns MutableStateFlow(listOf(stored))
        coEvery { repository.getTask(stored.id) } coAnswers { stored }
        coEvery { repository.upsertTask(any()) } returns Unit
        coEvery { conversations.recoverConversationRuntime(any(), any()) } returns 0
        coEvery { conversations.getConversation(any()) } returns null
        coEvery { conversations.upsertConversation(any()) } returns Unit
        coEvery {
            engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any())
        } returns result
        return Triple(repository, conversations, engine)
    }
}
