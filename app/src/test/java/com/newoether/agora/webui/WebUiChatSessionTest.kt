package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.viewmodel.QueuedSend
import kotlinx.coroutines.sync.Mutex
import com.newoether.agora.viewmodel.ChatClients
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.ForegroundSendTarget
import com.newoether.agora.viewmodel.ForegroundSendAdmission
import com.newoether.agora.viewmodel.SendAcceptance
import com.newoether.agora.viewmodel.testGenerationAdmissionSnapshot
import com.newoether.agora.viewmodel.GenerationStopAdapter
import com.newoether.agora.viewmodel.MessageGenerationController
import com.newoether.agora.viewmodel.NEW_CHAT_WORKSPACE_ID
import com.newoether.agora.viewmodel.NewChatWorkspaceSnapshot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiChatSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Sin TemporaryFolder: el fixture del session es field-initializer y corre antes que la Rule. */
    private val uploadDirectory: java.io.File =
        java.nio.file.Files.createTempDirectory("webui-uploads").toFile()

    private val conversations = mockk<ConversationRepository>(relaxed = true) {
        every { observeConversation("a") } returns
            flowOf(ChatConversation(id = "a", title = "Alpha", modelId = "conversation-model"))
        every { observeMessageTopology(any()) } returns flowOf(emptyList())
        coEvery { getConversation(any()) } returns null
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns MutableStateFlow(ConversationGenerationSnapshot())
        every { generating } returns MutableStateFlow(false)
        every { stopping } returns MutableStateFlow(false)
        every { queuedSends } returns MutableStateFlow(emptyList())
    }
    private val registry = mockk<ConversationStateRegistry> {
        every { getOrCreate(any()) } returns state
    }
    private val settings = mockk<SettingsRepository> {
        every { selectedModel } returns MutableStateFlow("default-model")
        every { enabledModels } returns MutableStateFlow(setOf("default-model", "conversation-model"))
        every { developerOptionsEnabled } returns MutableStateFlow(false)
        every { debugModelEnabled } returns MutableStateFlow(false)
        every { modelAliases } returns MutableStateFlow(emptyMap())
        every { modelProviderNames } returns MutableStateFlow(emptyMap())
        every { customProviders } returns MutableStateFlow(emptyList())
        coEvery { awaitInitialLoad() } just Runs
    }

    /** Arguments of every send-target capture: owner, current id, New Chat mode, entry, model, workspace. */
    private val captures = CopyOnWriteArrayList<Capture>()
    private val prepared = CopyOnWriteArrayList<ConversationComposerSnapshot>()
    private val generation = mockk<MessageGenerationController> {
        every {
            captureForegroundSendTarget(any(), any(), any(), any(), any(), any())
        } answers {
            val ownerId = arg<String>(0)
            val newChat = ownerId == NEW_CHAT_WORKSPACE_ID
            val workspace = if (newChat) arg<() -> NewChatWorkspaceSnapshot>(5).invoke() else null
            captures += Capture(ownerId, arg(1), arg(2), arg(3), arg(4), workspace)
            ForegroundSendTarget(
                ownerId = ownerId,
                conversationId = if (newChat) "created" else ownerId,
                runId = "run",
                wasNewChat = newChat,
                newChatEntryId = arg<Long>(3).takeIf { newChat },
                modelId = arg(4),
                newChatWorkspace = workspace,
            )
        }
        // Admission is refused, so each submission ends right after preparation.
        coEvery { prepareForegroundSend(any(), any(), any()) } answers {
            prepared += secondArg<ConversationComposerSnapshot>()
            null
        }
    }
    private val generationStop = mockk<GenerationStopAdapter>(relaxed = true)
    private val clients = ChatClients()

    private val session = WebUiChatSession(
        generation = generation,
        generationStop = generationStop,
        clients = clients,
        conversations = conversations,
        registry = registry,
        executionCoordinator = ConversationExecutionCoordinator(),
        settings = settings,
        transfers = mockk<ConversationSettingsTransferCoordinator>(relaxed = true),
        attachmentProcessor = mockk(relaxed = true),
        scope = scope,
        uploadDirectory = uploadDirectory,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aNewChatSendUsesTheSessionWorkspaceAndNeverThePhonesDrafts() = runBlocking {
        session.start()
        awaitModel()
        session.send("hello")
        withTimeout(TIMEOUT_MS) { while (prepared.isEmpty()) kotlinx.coroutines.delay(10) }

        val capture = captures.single()
        assertEquals(NEW_CHAT_WORKSPACE_ID, capture.ownerId)
        assertNull(capture.currentId)
        assertTrue(capture.isNewChat)
        assertEquals("default-model", capture.modelId)
        assertTrue(capture.workspace!!.sessionLocal)
        assertNull(capture.workspace.persisted)
        assertEquals("hello", prepared.single().text)
        coVerify(exactly = 0) { conversations.updateDraft(any(), any(), any(), any()) }
    }

    @Test
    fun anExistingConversationSendsWithItsOwnModelAndStopTargetsIt() = runBlocking {
        session.start()
        session.open("a", seq = 1L)
        awaitModel()
        withTimeout(TIMEOUT_MS) {
            while (captures.none { it.modelId == "conversation-model" }) {
                captures.clear()
                prepared.clear()
                session.send("question")
                withTimeout(TIMEOUT_MS) { while (prepared.isEmpty()) kotlinx.coroutines.delay(10) }
            }
        }
        val capture = captures.last()
        assertEquals("a", capture.ownerId)
        assertEquals("a", capture.currentId)
        assertFalse(capture.isNewChat)

        session.stop()
        verify(exactly = 1) { generationStop.stop("a", session) }
    }

    @Test
    fun onlyTheMatchingNewChatEntryFollowsItsAcceptedConversation() = runBlocking {
        session.start()
        session.open(null, seq = 7L)
        assertFalse(session.publishAcceptedNewConversation("created", "m", entryId = 0L))
        assertEquals(null, session.openTarget.value.conversationId)

        assertTrue(session.publishAcceptedNewConversation("created", "m", entryId = 1L))
        assertEquals(WebUiChatSession.OpenTarget("created", browserSeq = 7L, movedByServer = true), session.openTarget.value)
        assertEquals(listOf(session), clients.showing("created"))

        session.open("a", seq = 8L)
        assertFalse(session.publishAcceptedNewConversation("other", "m", entryId = 1L))
        assertEquals(WebUiChatSession.OpenTarget("a", browserSeq = 8L, movedByServer = false), session.openTarget.value)
    }

    @Test
    fun closingDetachesTheSessionFromTheRuntime() = runBlocking {
        session.start()
        session.open("a", seq = 1L)
        assertTrue(clients.isConversationOpen("a"))
        session.close()
        assertFalse(clients.isConversationOpen("a"))
    }
    @Test
    fun draftsAreSessionLocalAndOldTargetEditsAreRejected() = runBlocking {
        session.start()
        session.edit("new draft", revision = 1L, seq = 0L)
        assertEquals("new draft", session.composerState.first { it.editRevision == 1L }.text)
        session.open("a", seq = 2L)
        session.edit("conversation draft", revision = 1L, seq = 2L)
        session.edit("stale", revision = 2L, seq = 0L)
        assertEquals("conversation draft", session.composerState.first { it.seq == 2L }.text)
        session.open(null, seq = 3L)
        assertEquals("new draft", session.composerState.first { it.seq == 3L }.text)
        session.open(null, seq = 4L)
        assertEquals("new draft", session.composerState.first { it.seq == 4L }.text)
        coVerify(exactly = 0) { conversations.updateDraft(any(), any(), any(), any()) }
    }
    @Test
    fun newChatPublicationTransfersUnacceptedInputToTheCreatedOwner() = runBlocking {
        session.start()
        session.edit("remaining input", revision = 1L, seq = 0L)
        assertTrue(session.publishAcceptedNewConversation("created", "m", entryId = 0L))
        assertEquals("remaining input", session.composerState.first().text)
        session.open(null, seq = 1L)
        assertEquals("", session.composerState.first().text)
    }
    @Test
    fun staleSendAndStopCannotTargetTheNewSelection() = runBlocking {
        session.start()
        session.open("a", seq = 2L)
        session.send("stale send", seq = 1L, commandId = 7L)
        session.stop(seq = 1L)
        assertTrue(captures.isEmpty())
        verify(exactly = 0) { generationStop.stop(any(), any()) }
    }
    @Test
    fun modelSelectionSettlesBeforeTheNextTapAndNewChatDoesNotWritePhoneState() = runBlocking {
        session.start()
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid } }
        session.selectModel("conversation-model", seq = 0L, commandId = 1L)
        session.send("new choice", seq = 0L)
        withTimeout(TIMEOUT_MS) { while (captures.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("conversation-model", captures.single().modelId)
        coVerify(exactly = 0) { conversations.upsertNewChatPersist(any()) }
        val canonical = MutableStateFlow<ChatConversation?>(ChatConversation("a", "Alpha", modelId = "conversation-model"))
        val dao = mockk<ChatDao>()
        every { conversations.chatDao } returns dao
        every { conversations.observeConversation("a") } returns canonical
        coEvery { dao.updateConversationModel("a", "default-model", any()) } answers {
            canonical.value = canonical.value!!.copy(modelId = "default-model")
            1
        }
        session.open("a", seq = 2L)
        session.selectModel("default-model", seq = 2L, commandId = 2L)
        captures.clear()
        session.send("next tap", seq = 2L)
        withTimeout(TIMEOUT_MS) { while (captures.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals("default-model", captures.single().modelId)
        session.selectModel("invalid", seq = 2L, commandId = 3L)
        session.selectModel("conversation-model", seq = 1L, commandId = 4L)
        coVerify(exactly = 1) { dao.updateConversationModel(any(), any(), any()) }
    }
    @Test
    fun queueCommandsUseTheCanonicalOwnerAndRejectStaleSelections() = runBlocking {
        val queued = QueuedSend("q", "guidance", "default-model", emptyList(), "run")
        val queue = MutableStateFlow(listOf(queued))
        val generating = MutableStateFlow(true)
        var drains = 0
        every { state.queuedSends } returns queue
        every { state.generating } returns generating
        every { state.onQueueDrainRequested } returns { _: ConversationGenerationState -> drains++ }
        every { state.queueMutationMutex } returns Mutex()
        every { state.removeQueuedSend("q") } answers { queue.value = emptyList(); queued }
        // El facade portado (d8a0e1b3) llama discardQueuedSend tras remove; sin el stub,
        // mockk revienta asincrónicamente en IO y contamina al siguiente test de la JVM.
        every { state.discardQueuedSend(any()) } just Runs
        session.start()
        session.open("a", seq = 1L)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid && it.queue.size == 1 } }
        session.sendQueued(seq = 0L, commandId = 1L)
        session.removeQueued("q", seq = 0L)
        session.sendQueued(seq = 1L, commandId = 2L)
        assertEquals(0, drains)
        generating.value = false
        session.sendQueued(seq = 1L, commandId = 3L)
        assertEquals(1, drains)
        session.removeQueued("q", seq = 1L)
        withTimeout(TIMEOUT_MS) { queue.first { it.isEmpty() } }
        verify(exactly = 1) { state.removeQueuedSend("q") }
    }
    @Test
    fun canonicalAcceptanceClearsOnlyTheTapTextAndKeepsPostTapEdits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val accept = CompletableDeferred<Unit>()
        coEvery { generation.prepareForegroundSend(any(), any(), any()) } answers {
            val target = firstArg<ForegroundSendTarget>()
            ForegroundSendAdmission(
                target, testGenerationAdmissionSnapshot(target.conversationId, target.runId), null, null,
            )
        }
        coEvery { generation.sendMessage(any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            accept.await()
            SendAcceptance.Direct("accepted", "a").also {
                arg<suspend (SendAcceptance) -> Unit>(3).invoke(it)
            }
        }
        session.start()
        session.open("a", seq = 1L)
        withTimeout(TIMEOUT_MS) { session.composerState.first { it.modelValid } }
        session.edit("tap text", revision = 1L, seq = 1L)
        session.send("tap text", seq = 1L, commandId = 2L)
        withTimeout(TIMEOUT_MS) { entered.await() }
        session.edit("later edit", revision = 2L, seq = 1L)
        accept.complete(Unit)
        val settled = withTimeout(TIMEOUT_MS) { session.composerState.first { it.snapshot.acceptedVersion == 1L } }
        assertEquals("later edit", settled.text)
        assertEquals(2L, settled.editRevision)
        assertEquals(2L, settled.actionId)
        coVerify(exactly = 1) { generation.sendMessage(any(), "tap text", any(), any(), session) }
    }

    @Test
    fun aSessionLocalWorkspaceCarriesNoPhoneNewChatPersistSnapshot() {
        // Adaptación del fork: prepareForegroundSend vive en MessageGenerationController (el
        // upstream lo movió al builder); el gate sessionLocal ancla ahí.
        val controller = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/MessageGenerationController.kt")
        assertTrue(controller.contains("newChatPersistSnapshot = if (target.wasNewChat && workspace?.sessionLocal != true) {"))
    }

    private suspend fun awaitModel() {
        // The active model resolves once settings report their valid models.
        withTimeout(TIMEOUT_MS) { settings.enabledModels.first { it.isNotEmpty() } }
        kotlinx.coroutines.delay(50)
    }

    private data class Capture(
        val ownerId: String,
        val currentId: String?,
        val isNewChat: Boolean,
        val entryId: Long,
        val modelId: String,
        val workspace: NewChatWorkspaceSnapshot?,
    )

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
