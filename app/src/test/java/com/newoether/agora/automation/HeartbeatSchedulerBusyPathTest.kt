package com.newoether.agora.automation

import com.newoether.agora.AgoraApplication
import com.newoether.agora.di.AppContainer
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.HeartbeatManager
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.getRecentFinalModelResponses
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.service.HeartbeatNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Characterization tests for Busy-path semantics in HeartbeatScheduler.runHeartbeat.
 *
 * Contract (post-fix): when runOnce returns Busy the scheduler must NOT advance the
 * heartbeat watermark, must NOT record a log row, and must NOT send a push notification —
 * the 60s loop naturally retries. Failure advances the watermark (avoids hammering a
 * failing provider every 60s), records a log row, and notifies only when backgrounded.
 *
 * Red against current code (which advances the watermark and sends an empty
 * notification on Busy); green after the Busy early-return lands.
 */
class HeartbeatSchedulerBusyPathTest {

    private val heartbeatConversationId = "hb-conv"

    private fun scheduler(
        engineResult: TaskExecutionEngine.Result,
        inForeground: Boolean,
    ): Triple<HeartbeatScheduler, HeartbeatManager, HeartbeatNotifier> {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.awaitInitialLoad() } returns Unit
        every { settings.heartbeatConversationId } returns MutableStateFlow(heartbeatConversationId)
        every { settings.heartbeatModel } returns MutableStateFlow(null)
        every { settings.heartbeatPrompt } returns MutableStateFlow("")

        val manager = mockk<HeartbeatManager>(relaxed = true)

        val notifier = mockk<HeartbeatNotifier>(relaxed = true)
        val engine = mockk<TaskExecutionEngine>()
        coEvery {
            engine.runOnce(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns engineResult

        val conversationRepository = mockk<ConversationRepository>(relaxed = true)
        coEvery { conversationRepository.getRecentFinalModelResponses(any(), any()) } returns emptyList()

        val appContainer = mockk<AppContainer>(relaxed = true)
        every { appContainer.conversationRepository } returns conversationRepository
        every { appContainer.taskManager } returns mockk<TaskManager>(relaxed = true) {
            every { tasks } returns MutableStateFlow(emptyList())
        }
        every { appContainer.loopManager } returns mockk<LoopManager>(relaxed = true)
        every { appContainer.memoryManager } returns mockk<com.newoether.agora.data.MemoryManager>(relaxed = true)
        every { appContainer.taskRepository } returns mockk(relaxed = true)

        val application = mockk<AgoraApplication>()
        every { application.applicationContext } returns application
        every { application.requireContainer() } returns appContainer

        val scheduler = HeartbeatScheduler(
            appContext = application,
            heartbeatManager = manager,
            settingsRepository = settings,
            smsStore = mockk<SmsStore>(relaxed = true),
            smsPoller = mockk(relaxed = true),
            notificationStore = mockk<NotificationStore>(relaxed = true),
            heartbeatNotifier = notifier,
            taskExecutionEngine = engine,
            appForegroundTracker = mockk<AppForegroundTracker>(relaxed = true) {
                every { isInForeground } returns inForeground
            },
            loopManager = mockk(relaxed = true),
            emailStore = mockk<EmailStore>(relaxed = true),
            emailPoller = mockk<EmailPoller>(relaxed = true),
        )
        return Triple(scheduler, manager, notifier)
    }

    @Test
    fun busyDoesNotAdvanceWatermark() = runTest {
        val (scheduler, manager, _) = scheduler(
            engineResult = TaskExecutionEngine.Result.Busy(),
            inForeground = false,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 0) { manager.setLastHeartbeatEpochMs(any()) }
    }

    @Test
    fun busySendsNoNotificationEvenInBackground() = runTest {
        val (scheduler, _, notifier) = scheduler(
            engineResult = TaskExecutionEngine.Result.Busy(),
            inForeground = false,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 0) { notifier.sendHeartbeatNotification(any(), any()) }
    }

    @Test
    fun busyRecordsNoLogRow() = runTest {
        val (scheduler, manager, _) = scheduler(
            engineResult = TaskExecutionEngine.Result.Busy(),
            inForeground = true,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 0) { manager.recordHeartbeat(any(), any()) }
    }

    @Test
    fun failureAdvancesWatermarkAndLogs() = runTest {
        val (scheduler, manager, _) = scheduler(
            engineResult = TaskExecutionEngine.Result.Failure("provider exploded"),
            inForeground = true,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 1) { manager.setLastHeartbeatEpochMs(any()) }
        coVerify(exactly = 1) { manager.recordHeartbeat(false, "provider exploded") }
    }

    @Test
    fun failureNotifiesOnlyWhenBackgrounded() = runTest {
        val (backgrounded, _, bgNotifier) = scheduler(
            engineResult = TaskExecutionEngine.Result.Failure("provider exploded"),
            inForeground = false,
        )
        backgrounded.runHeartbeatNow()
        coVerify(exactly = 1) { bgNotifier.sendHeartbeatNotification(any(), any()) }

        val (foreground, _, fgNotifier) = scheduler(
            engineResult = TaskExecutionEngine.Result.Failure("provider exploded"),
            inForeground = true,
        )
        foreground.runHeartbeatNow()
        coVerify(exactly = 0) { fgNotifier.sendHeartbeatNotification(any(), any()) }
    }

    @Test
    fun successAdvancesWatermarkAndLogsOk() = runTest {
        val (scheduler, manager, _) = scheduler(
            engineResult = TaskExecutionEngine.Result.Success("msg-1", "HEARTBEAT_OK"),
            inForeground = false,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 1) { manager.setLastHeartbeatEpochMs(any()) }
        coVerify(exactly = 1) { manager.recordHeartbeat(true, null) }
    }
}
