package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.ui.chat.validGenerationParameters
import com.newoether.agora.ui.chat.withGenerationParameters
import com.newoether.agora.viewmodel.AttachmentImportProcessor
import com.newoether.agora.viewmodel.CompactFailureReason
import com.newoether.agora.viewmodel.CompactRequest
import com.newoether.agora.viewmodel.CompactResult
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor Advanced/Compact del navegador sobre el session real (no mockk). */
class WebUiChatSessionEditorsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shared = MutableStateFlow(mapOf("a" to ConversationSettings(thinkingEnabled = false, temperature = 0.7f)))
    private val preserve = MutableStateFlow(true)
    private val streaming = MutableStateFlow<com.newoether.agora.model.ChatMessage?>(null)
    private val stopping = MutableStateFlow(false)
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns MutableStateFlow(ConversationGenerationSnapshot())
        every { generating } returns MutableStateFlow(false)
        every { stopping } returns this@WebUiChatSessionEditorsTest.stopping
        every { streamingMessage } returns streaming
        every { queuedSends } returns MutableStateFlow(emptyList())
    }
    private val settings = mockk<SettingsRepository> {
        every { selectedModel } returns MutableStateFlow("OpenAI:test")
        every { enabledModels } returns MutableStateFlow(setOf("OpenAI:test", "OpenAI:compact"))
        every { developerOptionsEnabled } returns MutableStateFlow(false)
        every { debugModelEnabled } returns MutableStateFlow(false)
        every { modelAliases } returns MutableStateFlow(emptyMap())
        every { modelProviderNames } returns MutableStateFlow(emptyMap())
        every { customProviders } returns MutableStateFlow(emptyList())
        every { systemPrompts } returns MutableStateFlow(emptyList())
        every { activeSystemPromptId } returns MutableStateFlow(null)
        every { conversationSettings } returns shared
        every { codeExecutionEnabled } returns MutableStateFlow(false)
        every { googleSearchEnabled } returns MutableStateFlow(false)
        every { thinkingEnabled } returns MutableStateFlow(true)
        every { thinkingLevel } returns MutableStateFlow("medium")
        every { thinkingBudgetEnabled } returns MutableStateFlow(false)
        every { thinkingBudgetTokens } returns MutableStateFlow(4096)
        every { openAiServiceTierEnabled } returns MutableStateFlow(false)
        every { openAiServiceTier } returns MutableStateFlow("auto")
        every { openAiResponsesApiEnabled } returns MutableStateFlow(false)
        every { webSearchEnabled } returns MutableStateFlow(true)
        every { shellEnabled } returns MutableStateFlow(true)
        every { localLowContextModeEnabled } returns MutableStateFlow(false)
        every { maxContextWindow } returns MutableStateFlow(32768)
        every { defaultTemperature } returns MutableStateFlow(0.6f)
        every { defaultMaxTokens } returns MutableStateFlow(null)
        every { defaultTopP } returns MutableStateFlow(null)
        every { defaultFrequencyPenalty } returns MutableStateFlow(null)
        every { defaultPresencePenalty } returns MutableStateFlow(null)
        every { contextCompactModel } returns MutableStateFlow("OpenAI:compact")
        every { contextCompactPrompt } returns MutableStateFlow("configured summary")
        every { contextCompactRetainCount } returns MutableStateFlow(4)
        every { contextCompactPreserveSystemPrompt } returns preserve
        every { updateConversationSettings(any(), any()) } answers {
            val id = firstArg<String>()
            shared.value = shared.value + (id to secondArg<(ConversationSettings) -> ConversationSettings>()(shared.value[id] ?: ConversationSettings()))
        }
        io.mockk.coEvery { awaitInitialLoad() } returns Unit
    }
    private val conversations = mockk<ConversationRepository>(relaxed = true) {
        every { observeConversation(any()) } answers { flowOf(ChatConversation(firstArg(), "Test", modelId = "OpenAI:test")) }
        every { observeMessageTopology(any()) } returns flowOf(emptyList())
        coEvery { getConversation(any()) } returns null
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }
    private val generation = mockk<MessageGenerationController> {
        coEvery { compactManual(any(), any()) } returns CompactResult.NotNeeded
    }
    private val session by lazy { WebUiChatSession(
        generation, mockk<GenerationStopAdapter>(relaxed = true), com.newoether.agora.viewmodel.ChatClients(),
        conversations, mockk<ConversationStateRegistry> { every { getOrCreate(any()) } returns state },
        ConversationExecutionCoordinator(), settings, mockk<ConversationSettingsTransferCoordinator>(relaxed = true),
        mockk<AttachmentImportProcessor>(), scope,
        java.nio.file.Files.createTempDirectory("webui-editors").toFile(),
        compactFailureMessage = { it.reason.name },
    ) }
    @After fun finish() { scope.cancel() }
    private suspend fun ready(id: String? = null, seq: Long = 0): WebUiChatSession.ComposerState {
        session.open(id, seq)
        return withTimeout(5_000) { session.composerState.first { it.conversationId == id && it.seq == seq && it.modelValid } }
    }

    @Test fun advancedSaveUpdatesOnlyGenerationFieldsAndResetLeavesCurrentToolsIntact() = runBlocking {
        session.start()
        val initial = ready()
        assertEquals(0.6f, initial.generationDefaults.temperature)
        assertEquals("configured summary", initial.compactDefaults!!.prompt)
        session.settingCommand(WebSyncCommand("setting", setting = "thinkingEnabled", enabled = false))
        session.editorCommand(WebSyncCommand("advanced", parameters = ConversationSettings(temperature = 1.2f, thinkingEnabled = true)))
        val local = session.composerState.first()
        assertEquals(1.2f, local.generationParameters.temperature)
        assertEquals(false, local.generationParameters.thinkingEnabled)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
        ready("a", 1)
        shared.value = shared.value + ("a" to shared.value.getValue("a").copy(shellEnabled = false))
        val draft = ConversationSettings(contextWindow = 65536, temperature = 1f, maxTokens = 8192,
            topP = 0.5f, frequencyPenalty = -1f, presencePenalty = 2f, shellEnabled = true)
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 1, parameters = draft))
        // El editor aplica SOLO los campos de generación; los tools (thinking/shell) del target quedan.
        assertEquals(false, shared.value.getValue("a").thinkingEnabled)
        assertEquals(false, shared.value.getValue("a").shellEnabled)
        assertEquals(65536, shared.value.getValue("a").contextWindow)
        assertEquals(1f, shared.value.getValue("a").temperature)
        assertEquals(8192, shared.value.getValue("a").maxTokens)
        assertEquals(0.5f, shared.value.getValue("a").topP)
        assertEquals(-1f, shared.value.getValue("a").frequencyPenalty)
        assertEquals(2f, shared.value.getValue("a").presencePenalty)
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 1, parameters = ConversationSettings()))
        assertEquals(ConversationSettings(thinkingEnabled = false, shellEnabled = false), shared.value.getValue("a"))
    }

    @Test fun advancedRefusesInvalidStaleAndClosedRequestsWithoutMutation() = runBlocking {
        session.start()
        ready("a", 3)
        val original = shared.value
        listOf(ConversationSettings(temperature = Float.NaN), ConversationSettings(contextWindow = 20),
            ConversationSettings(maxTokens = 0), ConversationSettings(topP = 2f),
            ConversationSettings(frequencyPenalty = -3f), ConversationSettings(presencePenalty = Float.POSITIVE_INFINITY)).forEach {
            assertFalse(validGenerationParameters(it))
            session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 3, actionId = 2, parameters = it))
        }
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 2, parameters = ConversationSettings(temperature = 1f)))
        session.editorCommand(WebSyncCommand("advanced", conversationId = "old", seq = 3, parameters = ConversationSettings(temperature = 1f)))
        session.endUploads()
        session.editorCommand(WebSyncCommand("advanced", conversationId = "a", seq = 3, parameters = ConversationSettings(temperature = 1f)))
        assertEquals(original, shared.value)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }

    @Test fun compactKeepsCapturedOwnerRequestAndPreservePreferenceAcrossSelectionChange() = runBlocking {
        val started = CompletableDeferred<CompactRequest>()
        val release = CompletableDeferred<Unit>()
        coEvery { generation.compactManual("a", any()) } coAnswers {
            started.complete(secondArg()); release.await(); CompactResult.Created("compact")
        }
        session.start()
        ready("a", 7)
        val original = shared.value
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 7, actionId = 11, modelId = "OpenAI:compact", text = "own summary", retainCount = 0))
        val captured = withTimeout(5_000) { started.await() }
        assertEquals(11L, session.composerState.first().actionId)
        preserve.value = false
        ready("b", 8)
        release.complete(Unit)
        io.mockk.coVerify(timeout = 5_000, exactly = 1) { generation.compactManual("a", CompactRequest("OpenAI:compact", "own summary", 0, preserveSystemPrompt = true)) }
        assertTrue(captured.preserveSystemPrompt)
        assertEquals(original, shared.value)
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }

    @Test fun compactRejectsNewChatStaleInvalidAndStoppingRequests() = runBlocking {
        session.start()
        ready()
        val command = WebSyncCommand("compact", modelId = "OpenAI:compact", text = "summary", retainCount = 4)
        session.editorCommand(command)
        ready("a", 4)
        val selected = command.copy(conversationId = "a", seq = 4)
        listOf(command, selected.copy(modelId = "missing"), selected.copy(text = " "),
            selected.copy(retainCount = -1), selected.copy(retainCount = null)).forEach { session.editorCommand(it) }
        stopping.value = true
        session.editorCommand(selected)
        stopping.value = false
        streaming.value = com.newoether.agora.model.ChatMessage(
            id = com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX + "active",
            participant = com.newoether.agora.model.Participant.MODEL, text = "",
            status = com.newoether.agora.model.MessageStatus.SENDING)
        withTimeout(5_000) { session.composerState.first { it.compacting } }
        session.editorCommand(selected)
        io.mockk.coVerify(exactly = 0) { generation.compactManual(any(), any()) }
    }

    @Test fun compactFailureUsesCanonicalFormatterAndAcknowledgesWithoutSettingsWrites() = runBlocking {
        coEvery { generation.compactManual(any(), any()) } returns CompactResult.Failed(CompactFailureReason.GENERATION_BUSY)
        session.start()
        ready("a", 1)
        val failure = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { session.snackbars.first() }
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, actionId = 9, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        assertEquals("GENERATION_BUSY", withTimeout(5_000) { failure.await() })
        withTimeout(5_000) { session.composerState.first { it.actionId == 9L } }
        coEvery { generation.compactManual(any(), any()) } throws IllegalStateException("test failure")
        val generic = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { session.snackbars.first() }
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, actionId = 10, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        assertEquals("GENERIC", withTimeout(5_000) { generic.await() })
        verify(exactly = 0) { settings.updateConversationSettings(any(), any()) }
    }

    @Test fun recompactRoutesThroughTheRowPathOnlyForVisibleCompactRowsAndUsesConfiguredDefaults() = runBlocking {
        val started = CompletableDeferred<CompactRequest>()
        coEvery { generation.compactManual("a", any()) } coAnswers {
            started.complete(secondArg()); CompactResult.Created("compact")
        }
        session.start()
        ready("a", 7)
        // Un recompact de una fila que no es context compact visible jamás llega al owner.
        session.rowCommand(WebSyncCommand("recompact", conversationId = "a", seq = 7, messageId = "plain-row"), emptyList())
        // conversationId distinto del target también se fenced.
        session.rowCommand(WebSyncCommand("recompact", conversationId = "other", seq = 7, messageId = "plain-row"), emptyList())
        io.mockk.coVerify(exactly = 0) { generation.compactManual(any(), any()) }
        val compactRow = com.newoether.agora.model.ChatMessage(
            id = com.newoether.agora.util.Constants.COMPACT_MSG_PREFIX + "row",
            participant = com.newoether.agora.model.Participant.MODEL, text = "",
            status = com.newoether.agora.model.MessageStatus.SUCCESS)
        session.rowCommand(WebSyncCommand("recompact", conversationId = "a", seq = 7, messageId = compactRow.id, actionId = 12), listOf(compactRow))
        val captured = withTimeout(5_000) { started.await() }
        // El request reusa los defaults configurados del Compact y fija replaceMessageId.
        assertEquals("OpenAI:compact", captured.model)
        assertEquals("configured summary", captured.prompt)
        assertEquals(4, captured.retainLogicalMessages)
        assertEquals(compactRow.id, captured.replaceMessageId)
        assertEquals(true, captured.preserveSystemPrompt)
        withTimeout(5_000) { session.composerState.first { it.actionId == 12L } }
        Unit
    }

    @Test fun disconnectCancelsOnlySessionWaitAndDoesNotReplayCompact() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { generation.compactManual(any(), any()) } coAnswers {
            started.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        session.start()
        ready("a", 1)
        session.editorCommand(WebSyncCommand("compact", conversationId = "a", seq = 1, modelId = "OpenAI:compact", text = "summary", retainCount = 4))
        withTimeout(5_000) { started.await() }
        scope.cancel()
        withTimeout(5_000) { cancelled.await() }
        io.mockk.coVerify(exactly = 1) { generation.compactManual(any(), any()) }
    }
}
