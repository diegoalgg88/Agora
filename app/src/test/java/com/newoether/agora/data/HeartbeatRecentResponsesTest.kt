package com.newoether.agora.data

import com.newoether.agora.automation.HeartbeatScheduler
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.getRecentFinalModelResponses
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.HeartbeatNotifier
import com.newoether.agora.AgoraApplication
import com.newoether.agora.di.AppContainer
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the previous-heartbeat-results extraction in
 * [HeartbeatScheduler.buildPrompt]: the prompt passed to runOnce must carry the
 * last 3 FINAL model responses (blank tool-assembly rows and user rows excluded),
 * newest-first under the "Most recent" label.
 *
 * Red against the current graph-fetch extraction; green after the bounded
 * final-response tail query lands.
 */
class HeartbeatRecentResponsesTest {

    private val heartbeatConversationId = "hb-conv"

    private fun messageEntity(
        id: String,
        participant: Participant,
        text: String,
        toolCallJson: String? = null,
        status: MessageStatus = MessageStatus.SUCCESS,
        runId: String,
        timestamp: Long,
        sequence: Long,
    ) = MessageEntity(
        id = id,
        conversationId = heartbeatConversationId,
        parentId = null,
        text = text,
        participant = participant,
        status = status,
        timestamp = timestamp,
        runId = runId,
        runSequence = sequence,
        toolCallJson = toolCallJson,
    )

    private fun terminalRun(id: String, startedAt: Long) = RunEntity(
        id = id,
        conversationId = heartbeatConversationId,
        parentRunId = null,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = startedAt,
        lastCheckpointAt = startedAt,
        endedAt = startedAt,
        endReason = RunEndReason.MODEL_COMPLETED,
    )

    /** Builds the scheduler with mocked collaborators; returns it with the captured prompt slot. */
    private fun schedulerHarness(
        messages: List<MessageEntity>,
    ): Pair<HeartbeatScheduler, CapturingSlot<String>> {
        val conversationRepository = mockk<ConversationRepository>(relaxed = true)
        coEvery { conversationRepository.getRecentFinalModelResponses(heartbeatConversationId, any()) } coAnswers {
            val limit = secondArg<Int>()
            messages.filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .sortedByDescending { it.timestamp }
                .take(limit)
        }

        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.awaitInitialLoad() } returns Unit
        every { settings.heartbeatConversationId } returns MutableStateFlow(heartbeatConversationId)
        every { settings.heartbeatModel } returns MutableStateFlow(null)
        every { settings.heartbeatPrompt } returns MutableStateFlow("")

        val engine = mockk<TaskExecutionEngine>()
        val promptSlot = slot<String>()
        coEvery {
            engine.runOnce(any(), capture(promptSlot), any(), any(), any(), any(), any(), any(), any())
        } returns TaskExecutionEngine.Result.Success("msg-1", "HEARTBEAT_OK")

        val appContainer = mockk<AppContainer>(relaxed = true)
        every { appContainer.conversationRepository } returns conversationRepository
        every { appContainer.taskManager } returns mockk<TaskManager>(relaxed = true) {
            every { tasks } returns MutableStateFlow(emptyList())
        }
        every { appContainer.loopManager } returns mockk<LoopManager>(relaxed = true)
        every { appContainer.memoryManager } returns mockk<MemoryManager>(relaxed = true)
        every { appContainer.taskRepository } returns mockk(relaxed = true)

        val application = mockk<AgoraApplication>()
        every { application.applicationContext } returns application
        every { application.requireContainer() } returns appContainer

        val scheduler = HeartbeatScheduler(
            appContext = application,
            heartbeatManager = mockk(relaxed = true),
            settingsRepository = settings,
            smsStore = mockk<SmsStore>(relaxed = true),
            smsPoller = mockk(relaxed = true),
            notificationStore = mockk<NotificationStore>(relaxed = true),
            heartbeatNotifier = mockk<HeartbeatNotifier>(relaxed = true),
            taskExecutionEngine = engine,
            appForegroundTracker = mockk<AppForegroundTracker>(relaxed = true) {
                every { isInForeground } returns true
            },
            loopManager = mockk(relaxed = true),
            emailStore = mockk<EmailStore>(relaxed = true),
            emailPoller = mockk<EmailPoller>(relaxed = true),
        )
        return scheduler to promptSlot
    }

    @Test
    fun promptContainsOnlyFinalModelResponsesNotToolAssemblyRows() = runTest {
        val messages = listOf(
            // Run 1: heartbeat prompt (USER), tool-assembly row (MODEL, blank, toolCallJson set), final response
            messageEntity("u1", Participant.USER, "[HEARTBEAT] ...", runId = "run-1", timestamp = 1_000, sequence = 0),
            messageEntity(
                "t1", Participant.MODEL, text = "", toolCallJson = "[{\"type\":\"tool\"}]",
                runId = "run-1", timestamp = 1_001, sequence = -1,
            ),
            messageEntity("m1", Participant.MODEL, "First heartbeat summary", runId = "run-1", timestamp = 1_002, sequence = 1),
            // Run 2: another tool row + final response
            messageEntity(
                "t2", Participant.MODEL, text = "", toolCallJson = "[{\"type\":\"tool\"}]",
                runId = "run-2", timestamp = 2_001, sequence = -1,
            ),
            messageEntity("m2", Participant.MODEL, "Second heartbeat summary", runId = "run-2", timestamp = 2_002, sequence = 1),
        )

        val (scheduler, promptSlot) = schedulerHarness(messages)
        scheduler.runHeartbeatNow()

        val prompt = promptSlot.captured
        assertTrue("Previous results must include the newest final response", prompt.contains("Second heartbeat summary"))
        assertTrue("Previous results must include the older final response", prompt.contains("First heartbeat summary"))
        assertFalse(
            "Tool-assembly rows must never appear as previous results",
            Regex("### Most recent\\n+\\n*###").containsMatchIn(prompt),
        )
    }

    @Test
    fun newestResponseIsLabeledMostRecent() = runTest {
        val messages = listOf(
            messageEntity("m1", Participant.MODEL, "older response", runId = "run-1", timestamp = 1_002, sequence = 1),
            messageEntity("m2", Participant.MODEL, "newer response", runId = "run-2", timestamp = 2_002, sequence = 1),
        )

        val (scheduler, promptSlot) = schedulerHarness(messages)
        scheduler.runHeartbeatNow()

        val prompt = promptSlot.captured
        val mostRecentIdx = prompt.indexOf("Most recent")
        val newerIdx = prompt.indexOf("newer response")
        val olderIdx = prompt.indexOf("older response")
        assertTrue("Must contain the Most recent label", mostRecentIdx >= 0)
        assertTrue(
            "The newest response must be under the 'Most recent' label",
            newerIdx > mostRecentIdx && (olderIdx == -1 || newerIdx < olderIdx),
        )
    }

    @Test
    fun atMostThreePreviousResponsesAreIncluded() = runTest {
        val messages = (1..5).map { i ->
            messageEntity(
                "m$i", Participant.MODEL, "response number $i",
                runId = "run-$i", timestamp = 1_000L + i, sequence = 1,
            )
        }

        val (scheduler, promptSlot) = schedulerHarness(messages)
        scheduler.runHeartbeatNow()

        val prompt = promptSlot.captured
        assertTrue(prompt.contains("response number 5"))
        assertTrue(prompt.contains("response number 4"))
        assertTrue(prompt.contains("response number 3"))
        assertFalse("Older responses beyond the last 3 must be excluded", prompt.contains("response number 1"))
        assertFalse(prompt.contains("response number 2"))
    }

    @Test
    fun noPreviousResultsSectionWhenConversationHasNoFinalResponses() = runTest {
        val messages = listOf(
            messageEntity("u1", Participant.USER, "[HEARTBEAT]", runId = "run-1", timestamp = 1_000, sequence = 0),
            messageEntity(
                "t1", Participant.MODEL, text = "", toolCallJson = "[]",
                runId = "run-1", timestamp = 1_001, sequence = -1,
            ),
        )

        val (scheduler, promptSlot) = schedulerHarness(messages)
        scheduler.runHeartbeatNow()

        val prompt = promptSlot.captured
        assertFalse(
            "No Previous Results section when only blank/tool rows exist",
            prompt.contains("Previous Heartbeat Results"),
        )
    }
}
