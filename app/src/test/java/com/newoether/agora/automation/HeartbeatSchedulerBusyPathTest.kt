package com.newoether.agora.automation

import com.newoether.agora.AgoraApplication
import com.newoether.agora.di.AppContainer
import com.newoether.agora.data.EmailPoller
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.HeartbeatManager
import com.newoether.agora.data.NotificationStore
import com.newoether.agora.data.SmsPoller
import com.newoether.agora.data.SmsStore
import com.newoether.agora.data.TaskConfirmationStore
import com.newoether.agora.data.local.TaskConfirmationCardStyle
import com.newoether.agora.data.local.TaskConfirmationMode
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

    init {
        // The staging-isolation path (surfaceTaskConfirmation's catch) logs failures through
        // DebugLog.e, which hits android.util.Log — not mocked on the JVM. Silence it for the
        // whole suite (same pattern as ProviderRetryRequestResolutionTest).
        io.mockk.mockkObject(com.newoether.agora.util.DebugLog)
        io.mockk.every {
            com.newoether.agora.util.DebugLog.e(any(), any())
        } answers { Unit }
        io.mockk.every {
            com.newoether.agora.util.DebugLog.e(any(), any(), any())
        } answers { Unit }
        io.mockk.every {
            com.newoether.agora.util.DebugLog.w(any(), any(), any())
        } answers { Unit }
    }

    private val heartbeatConversationId = "hb-conv"

    /** Mock staged row returned by the confirmation store when a stage() happens. */
    private fun stagedRow(id: String) = com.newoether.agora.data.local.TaskConfirmationEntity(
        id = id,
        sourceType = com.newoether.agora.data.local.TaskConfirmationSource.HEARTBEAT.name,
        conversationId = heartbeatConversationId,
        modelMessageId = "staged-$id",
        title = "title",
        bodyText = "body",
        createdAtEpochMs = 0L,
        remindAtEpochMs = null,
        status = com.newoether.agora.data.local.TaskConfirmationStatus.PENDING.name,
    )

    private fun scheduler(
        engineResult: TaskExecutionEngine.Result,
        inForeground: Boolean,
        confirmationToggleOn: Boolean = false,
    ): Triple<HeartbeatScheduler, HeartbeatManager, HeartbeatNotifier> {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.awaitInitialLoad() } returns Unit
        every { settings.heartbeatConversationId } returns MutableStateFlow(heartbeatConversationId)
        every { settings.heartbeatModel } returns MutableStateFlow(null)
        every { settings.heartbeatPrompt } returns MutableStateFlow("")
        // buildPrompt reads the connected account list for the Email Account Status section;
        // a relaxed StateFlow mock would explode on .value iteration.
        every { settings.emailAccounts } returns MutableStateFlow(emptyList<com.newoether.agora.data.EmailAccount>())
        every { settings.taskConfirmationEnabled } returns MutableStateFlow(confirmationToggleOn)
        every { settings.taskConfirmationMode } returns MutableStateFlow(TaskConfirmationMode.PROMPT)
        every { settings.taskConfirmationCardStyle } returns
            MutableStateFlow(TaskConfirmationCardStyle.BOTTOM)

        val manager = mockk<HeartbeatManager>(relaxed = true)

        val notifier = mockk<HeartbeatNotifier>(relaxed = true)
        val engine = mockk<TaskExecutionEngine>()
        // eq(true) pins the contract: the heartbeat owns its notification policy and always
        // suppresses the generic "Response ready" terminal notification (see automation.md §5).
        coEvery {
            engine.runOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(true))
        } returns engineResult

        val confirmationStore = mockk<TaskConfirmationStore>(relaxed = true)
        coEvery { confirmationStore.consumeDueReminders(any()) } returns emptyList()
        coEvery { confirmationStore.cleanupOld(any()) } returns 0
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true

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
            taskConfirmationStore = confirmationStore,
            taskPromptNotifier = promptNotifier,
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

    // ── Rich task confirmations (plan PLAN-20261002-TASK-CONFIRM) ────────────
    // Extends the matrix WITHOUT weakening any pre-existing case above.

    @Test
    fun heartbeatOkSentinelIsSilentEvenWithToggleOn() = runTest {
        val store = stageCapturingStore()
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-2", "HEARTBEAT_OK"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
        ).first.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
    }

    @Test
    fun heartbeatOkCaseInsensitiveIsSilent() = runTest {
        val store = stageCapturingStore()
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-3", "  heartbeat_ok  "),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
        ).first.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
    }

    @Test
    fun heartbeatOkPrefixWithSubstantiveContentStagesStrippedRemainder() = runTest {
        val store = stageCapturingStore()
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success(
                "msg-4",
                "HEARTBEAT_OK — el backup lleva 3 días fallando",
            ),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
        ).first.runHeartbeatNow()
        coVerify(exactly = 1) { store.stage(withArg { draft ->
            org.junit.Assert.assertEquals("el backup lleva 3 días fallando", draft.bodyText)
        }) }
    }

    @Test
    fun toggleOffKeepsSilentBehaviorForActionableHeartbeat() = runTest {
        val store = stageCapturingStore()
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-5", "algo importante"),
            inForeground = false,
            confirmationToggleOn = false,
            store = store,
        ).first.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
    }

    @Test
    fun actionableHeartbeatInForegroundStagesButDoesNotPost() = runTest {
        val store = stageCapturingStore()
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-6", "algo importante"),
            inForeground = true,
            confirmationToggleOn = true,
            store = store,
            promptNotifier = promptNotifier,
        ).first.runHeartbeatNow()
        coVerify(exactly = 1) { store.stage(any()) }
        coVerify(exactly = 0) { promptNotifier.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun actionableHeartbeatInBackgroundStagesAndPosts() = runTest {
        val store = stageCapturingStore()
        coEvery { store.stage(any()) } answers { stagedRow("staged-1") }
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-7", "algo importante"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
            promptNotifier = promptNotifier,
        ).first.runHeartbeatNow()
        coVerify(exactly = 1) { store.stage(any()) }
        coVerify(exactly = 1) { promptNotifier.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun alreadyResolvedDedupRowIsNeverReposted() = runTest {
        // stage() is idempotent and hands back the existing row of a re-staged generation. If
        // that row was already resolved, posting it would resurrect a prompt with dead actions.
        val store = stageCapturingStore()
        coEvery { store.stage(any()) } answers {
            stagedRow("resolved").copy(
                status = com.newoether.agora.data.local.TaskConfirmationStatus.ACKNOWLEDGED.name,
            )
        }
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-dup", "algo importante"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
            promptNotifier = promptNotifier,
        ).first.runHeartbeatNow()
        coVerify(exactly = 1) { store.stage(any()) }
        coVerify(exactly = 0) { promptNotifier.post(any(), any(), any(), any(), any()) }
    }

    @Test
    fun autoModePostsInfoWithoutStaging() = runTest {
        // AUTO (Universal Installer's AutoNotification analogue): informational only.
        // Nothing may be staged (no durable row, no banner) and the post is postInfo.
        val store = stageCapturingStore()
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-auto", "algo importante"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
            mode = TaskConfirmationMode.AUTO,
            promptNotifier = promptNotifier,
        ).first.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
        coVerify(exactly = 0) { promptNotifier.post(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { promptNotifier.postInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun autoModeInForegroundDoesNotPost() = runTest {
        // Foreground suppresses even the informational post, same as PROMPT mode.
        val store = stageCapturingStore()
        val promptNotifier = mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true)
        every { promptNotifier.canPost() } returns true
        schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-auto-fg", "algo importante"),
            inForeground = true,
            confirmationToggleOn = true,
            store = store,
            mode = TaskConfirmationMode.AUTO,
            promptNotifier = promptNotifier,
        ).first.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
        coVerify(exactly = 0) { promptNotifier.postInfo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun stagingFailureNeverSkipsWatermarkAndRunLog() = runTest {
        // The run already succeeded and its snapshot is consumed: a staging crash must not
        // leave the watermark behind, or the 60s loop re-runs a completed heartbeat.
        val store = stageCapturingStore()
        coEvery { store.stage(any()) } throws IllegalStateException("room exploded")
        val (scheduler, manager, _) = schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-boom", "algo importante"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 1) { manager.setLastHeartbeatEpochMs(any()) }
        coVerify(exactly = 1) { manager.recordHeartbeat(true, null) }
    }

    @Test
    fun markdownOnlyResultCollapsesToSilentAndIsNeverStaged() = runTest {
        val store = stageCapturingStore()
        val (scheduler, manager, _) = schedulerWith(
            engineResult = TaskExecutionEngine.Result.Success("msg-md", "**###**"),
            inForeground = false,
            confirmationToggleOn = true,
            store = store,
        )
        scheduler.runHeartbeatNow()
        coVerify(exactly = 0) { store.stage(any()) }
        coVerify(exactly = 1) { manager.recordHeartbeat(true, null) }
    }

    @Test
    fun failurePathDoesNotStageConfirmationRegardlessOfToggle() = runTest {
        listOf(false, true).forEach { toggle ->
            val store = stageCapturingStore()
            schedulerWith(
                engineResult = TaskExecutionEngine.Result.Failure("provider exploded"),
                inForeground = false,
                confirmationToggleOn = toggle,
                store = store,
            ).first.runHeartbeatNow()
            coVerify(exactly = 0) { store.stage(any()) }
        }
    }

    // ── Test scaffolding for the confirmation-aware cases ────────────────────

    private fun stageCapturingStore(): TaskConfirmationStore =
        mockk<TaskConfirmationStore>(relaxed = true) {
            coEvery { consumeDueReminders(any()) } returns emptyList()
            coEvery { cleanupOld(any()) } returns 0
            coEvery { stage(any()) } returns stagedRow("staged")
        }

    private fun schedulerWith(
        engineResult: TaskExecutionEngine.Result,
        inForeground: Boolean,
        confirmationToggleOn: Boolean,
        store: TaskConfirmationStore,
        mode: TaskConfirmationMode = TaskConfirmationMode.PROMPT,
        promptNotifier: com.newoether.agora.service.TaskPromptNotifier =
            mockk<com.newoether.agora.service.TaskPromptNotifier>(relaxed = true) {
                every { canPost() } returns true
                // Happy path: a staged prompt posts successfully, no deferred retry.
                every { post(any(), any(), any(), any(), any()) } returns true
                every { postInfo(any(), any(), any(), any(), any()) } returns true
            },
    ): Triple<HeartbeatScheduler, HeartbeatManager, HeartbeatNotifier> {
        // Reuse the main scheduler() harness but inject the capturing store/notifier by
        // re-running with overridden dependencies: simplest approach is a direct call.
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.awaitInitialLoad() } returns Unit
        every { settings.heartbeatConversationId } returns MutableStateFlow(heartbeatConversationId)
        every { settings.heartbeatModel } returns MutableStateFlow(null)
        every { settings.heartbeatPrompt } returns MutableStateFlow("")
        every { settings.emailAccounts } returns MutableStateFlow(emptyList<com.newoether.agora.data.EmailAccount>())
        every { settings.taskConfirmationEnabled } returns MutableStateFlow(confirmationToggleOn)
        every { settings.taskConfirmationMode } returns MutableStateFlow(mode)
        every { settings.taskConfirmationCardStyle } returns
            MutableStateFlow(TaskConfirmationCardStyle.BOTTOM)

        val manager = mockk<HeartbeatManager>(relaxed = true)
        val notifier = mockk<HeartbeatNotifier>(relaxed = true)
        val engine = mockk<TaskExecutionEngine>()
        // Same contract pin as the scheduler() harness above.
        coEvery {
            engine.runOnce(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(true))
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
            taskConfirmationStore = store,
            taskPromptNotifier = promptNotifier,
        )
        return Triple(scheduler, manager, notifier)
    }
}
