package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.observeConversationTodo
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingSegmentDisplayModes
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.viewmodel.ComposerSubmissionPhase
import com.newoether.agora.viewmodel.ConversationComposerSubmissionSnapshot
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import com.newoether.agora.viewmodel.ConversationGenerationState
import com.newoether.agora.viewmodel.ConversationMessagePayloadHydration
import com.newoether.agora.viewmodel.ConversationStateRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import com.newoether.agora.util.DebugLog
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebUiSyncTest {
    private val list = MutableStateFlow(
        listOf(
            ChatConversation(id = "a", title = "Alpha", selectedBranchesJson = """{"null":"root","root":"m1"}"""),
            ChatConversation(id = "b", title = "Beta", hasUnreadGeneration = true),
        ),
    )
    private val topology = MutableStateFlow(
        listOf(
            topology("root", parent = null, Participant.USER, timestamp = 1),
            topology("m1", parent = "root", Participant.MODEL, timestamp = 2),
            // Newer sibling: without the saved selection the path would take it.
            topology("m2", parent = "root", Participant.MODEL, timestamp = 3),
        ),
    )
    private val snapshot = MutableStateFlow(ConversationGenerationSnapshot())
    private val bodies = mapOf(
        "root" to message("root", null, Participant.USER, "question"),
        "m1" to message("m1", "root", Participant.MODEL, "x \\(a^2\\) y"),
        "m2" to message("m2", "root", Participant.MODEL, "other"),
    )
    private val conversations = mockk<ConversationRepository> {
        every { observeDrawerConversations(any()) } answers {
            val limit = firstArg<Int>()
            list.map { rows ->
                rows.take(limit).map {
                    com.newoether.agora.data.local.DrawerConversationRow(
                        it.id, it.title, it.hasUnreadGeneration, it.isPinned,
                    )
                }
            }
        }
        every { observeConversation(any()) } answers {
            val id = firstArg<String>()
            list.map { rows -> rows.find { it.id == id } }
        }
        coEvery { setConversationPinned(any(), any()) } returns true
        // El stub del DAO crudo: la extensión observeConversationTodo decodifica encima.
        every { chatDao } returns mockk<com.newoether.agora.data.local.ChatDao> {
            every { observeConversationTodo(any()) } returns MutableStateFlow<String?>(null)
            // El mirror de subagents consume estas dos queries del DAO crudo.
            every { observeParentConversationId(any()) } returns kotlinx.coroutines.flow.flowOf(null)
            every { observeSubagents(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        }
        every { observeMessageTopology("a") } returns topology
        coEvery { recoverConversationRuntime(any(), any()) } returns 0
    }
    private val state = mockk<ConversationGenerationState> {
        every { generationSnapshot } returns snapshot
    }
    private val registry = mockk<ConversationStateRegistry> {
        every { activeConversationIds } returns MutableStateFlow(setOf("b"))
        every { getOrCreate("a") } returns state
    }
    private val hydration = mockk<ConversationMessagePayloadHydration> {
        every { observeMessage(any(), any()) } answers { flowOf(bodies[firstArg<String>()]) }
    }

    private val openTarget = MutableStateFlow(WebUiChatSession.OpenTarget(null, browserSeq = 0L, movedByServer = false))
    private val snackbars = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val scrollRequests = MutableSharedFlow<WebSyncEvent.ScrollToBottom>(extraBufferCapacity = 4)
    private val composerState = MutableSharedFlow<WebUiChatSession.ComposerState>(extraBufferCapacity = 4)
    private val session = mockk<WebUiChatSession> {
        every { openTarget } returns this@WebUiSyncTest.openTarget
        every { snackbars } returns this@WebUiSyncTest.snackbars
        every { scrollRequests } returns this@WebUiSyncTest.scrollRequests
        every { composerState } returns this@WebUiSyncTest.composerState
        every { branchTransitions } returns com.newoether.agora.viewmodel.BranchReplacementTransitionCoordinator()
        coEvery { start() } just Runs
        coEvery { close() } just Runs
        // serve() del paso 2057da82 llama endUploads() en el finally; sin el stub mockk
        // explota y contamina el test (misma lección que discardQueuedSend).
        coEvery { endUploads() } just Runs
        coEvery { send(any(), any(), any()) } just Runs
        coEvery { edit(any(), any(), any()) } just Runs
        coEvery { cancelWaiting(any(), any()) } just Runs
        coEvery { settingCommand(any()) } just Runs
        coEvery { editorCommand(any()) } just Runs
        every { stop(any()) } just Runs
        coEvery { open(any(), any()) } answers {
            this@WebUiSyncTest.openTarget.value =
                WebUiChatSession.OpenTarget(firstArg(), secondArg(), movedByServer = false)
        }
    }

    @Test
    fun sendAndStopCommandsGoToTheConnectionSession() = sync { send, _ ->
        send("""{"type":"send","text":"hello"}""")
        send("""{"type":"stop"}""")
        coVerify(exactly = 1) { session.start() }
        coVerify(exactly = 1) { session.send("hello", 0L, 0L) }
        verify(exactly = 1) { session.stop(0L) }
    }
    @Test
    fun draftAndCancelCommandsKeepTheirExactTargetAndActionIdentity() = sync { send, _ ->
        send("""{"type":"draft","text":"later edit","revision":3,"seq":7}""")
        send("""{"type":"cancel_waiting","seq":7,"actionId":9}""")
        coVerify(exactly = 1) { session.edit("later edit", 3L, 7L) }
        coVerify(exactly = 1) { session.cancelWaiting(7L, 9L) }
    }

    @Test
    fun settingsAndEditorCommandsKeepTheirExactDecodedPayload() = sync { send, _ ->
        send("""{"type":"setting","seq":7,"actionId":9,"setting":"webSearchEnabled","enabled":false}""")
        send("""{"type":"advanced","seq":7,"actionId":10,"parameters":{"temperature":1.2}}""")
        send("""{"type":"compact","seq":7,"actionId":11,"modelId":"m","text":"summary","retainCount":0}""")
        coVerify(exactly = 1) { session.settingCommand(WebSyncCommand("setting", seq = 7, actionId = 9, setting = "webSearchEnabled", enabled = false)) }
        coVerify(exactly = 1) { session.editorCommand(WebSyncCommand("advanced", seq = 7, actionId = 10, parameters = com.newoether.agora.data.ConversationSettings(temperature = 1.2f))) }
        coVerify(exactly = 1) { session.editorCommand(WebSyncCommand("compact", seq = 7, actionId = 11, modelId = "m", text = "summary", retainCount = 0)) }
    }

    @Test
    fun aRuntimeMoveIsAnnouncedBeforeTheMovedConversationsEvents() = sync { send, received ->
        send("""{"type":"open","conversationId":null,"seq":4}""")
        received()
        openTarget.value = WebUiChatSession.OpenTarget("a", browserSeq = 4L, movedByServer = true)
        val events = received()
        val opened = events.indexOfFirst { it.type == "opened" }
        assertEquals("a", events[opened].string("conversationId"))
        assertEquals("4", events[opened].string("seq"))
        assertTrue(opened < events.indexOfFirst { it.type == "path" })
    }

    @Test
    fun browserOpensAreNotEchoedAsMoves() = sync { send, received ->
        send("""{"type":"open","conversationId":"a","seq":1}""")
        val events = received()
        assertTrue(events.none { it.type == "opened" })
        assertEquals(listOf("root", "m1"), events.single { it.type == "path" }.ids())
    }

    @Test
    fun snackbarsAndComposerPhasesAreForwarded() = sync { _, received ->
        received()
        snackbars.emit("No model selected")
        composerState.emit(
            WebUiChatSession.ComposerState(
                conversationId = null,
                snapshot = ConversationComposerSubmissionSnapshot(
                    phase = ComposerSubmissionPhase.WAITING,
                    acceptedVersion = 2L,
                ),
                text = "canonical draft",
                editRevision = 3L,
                actionId = 9L,
            ),
        )
        val events = received()
        assertEquals("No model selected", events.single { it.type == "snackbar" }.string("message"))
        val composer = events.single { it.type == "composer" }
        assertEquals("WAITING", composer.string("phase"))
        assertEquals("2", composer.string("acceptedVersion"))
        assertEquals("canonical draft", composer.string("text"))
        assertEquals("3", composer.string("editRevision"))
        assertEquals("9", composer.string("actionId"))
    }

    @Test
    fun closingTheConnectionClosesItsSession() = runTest {
        val incoming = Channel<String>(Channel.UNLIMITED)
        incoming.close()
        webUiSync(StandardTestDispatcher(testScheduler)).serve("login", incoming) { }
        coVerify(exactly = 1) { session.close() }
    }
    @Test
    fun acceptedScrollRequestsKeepTheMessageAndBrowserTarget() = sync { _, received ->
        received()
        scrollRequests.emit(WebSyncEvent.ScrollToBottom("a", "accepted-message", 7L))
        val event = received().single { it.type == "scroll_to_bottom" }
        assertEquals("a", event.string("conversationId"))
        assertEquals("accepted-message", event.string("messageId"))
        assertEquals("7", event.string("seq"))
    }

    @Test
    fun listThenOpenSendsTheSavedBranchAfterRecovery() = sync { send, received ->
        val first = received()
        // Divergencia del fork: el evento connection (write block) viaja inline antes del display,
        // que llega en el mismo primer batch con replay=1 — el frontend lo dereferencea sin
        // optional chaining en cuanto abre el composer.
        assertTrue(first.any { it.string("type") == "display" })
        val listed = first.single { it.type == "conversations" }
        val items = listed["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b"), items.map { it.string("id") })
        assertEquals(listOf("false", "true"), items.map { it.string("generating") })
        assertEquals("true", items[1].string("unread"))

        send("""{"type":"open","conversationId":"a"}""")
        val path = received().single { it.type == "path" }
        assertEquals(listOf("root", "m1"), path.ids())
        coVerify(exactly = 1) { conversations.recoverConversationRuntime("a", any()) }
    }

    @Test
    fun onlyWatchedRowsOnThePathGetBodiesWithMathSplitOut() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        send("""{"type":"watch","messageIds":["m1","m2"]}""")
        val payloads = received().filter { it.type == "payload" }.map { it["message"]!!.jsonObject }
        assertEquals(listOf("m1"), payloads.map { it.string("id") })
        val text = payloads.single()["text"]!!.jsonObject
        // parseLatexSpans moves the spaces around inline math into the formula's source.
        assertEquals("x${MATH_OPEN}0${MATH_CLOSE}y", text.string("markdown"))
        val math = text["math"]!!.jsonArray.single().jsonObject
        assertEquals("a^2", math.string("tex"))
        assertEquals("false", math.string("display"))
        verify(exactly = 0) { hydration.observeMessage("m2", any()) }
    }

    @Test
    fun streamingMessageJoinsThePathAndIsSentWithItsBody() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        snapshot.value = ConversationGenerationSnapshot(
            conversationId = "a",
            streamingMessage = message("m3", "m1", Participant.MODEL, "partial", MessageStatus.SENDING),
            isLoading = true,
            isGenerating = true,
        )
        val events = received()
        val path = events.single { it.type == "path" }
        assertEquals(listOf("root", "m1", "m3"), path.ids())
        assertEquals("true", path.string("generating"))
        val streaming = events.single { it.type == "streaming" }["message"]!!.jsonObject
        assertEquals("partial", streaming["text"]!!.jsonObject.string("markdown"))
    }

    @Test
    fun openingAConversationThatIsGoneReportsItDeletedWithoutRecovery() = sync { send, received ->
        send("""{"type":"open","conversationId":"gone"}""")
        assertEquals("gone", received().single { it.type == "deleted" }.string("conversationId"))
        coVerify(exactly = 0) { conversations.recoverConversationRuntime(any(), any()) }
    }

    @Test
    fun rowRemovedFromTheListWhileOpenIsReportedDeleted() = sync { send, received ->
        send("""{"type":"open","conversationId":"a"}""")
        received()
        list.value = list.value.filterNot { it.id == "a" }
        assertTrue(received().any { it.type == "deleted" && it.string("conversationId") == "a" })
    }

    @Test
    fun aFailedLoadEndsThatConversationButNotTheConnection() {
        every { conversations.observeMessageTopology("a") } returns flow { error("broken graph") }
        mockkObject(DebugLog)
        every { DebugLog.e(any(), any(), any()) } just Runs
        try {
            sync { send, received ->
                send("""{"type":"open","conversationId":"a"}""")
                assertTrue(received().any { it.type == "load_failed" })
                list.value = list.value.map { it.copy(title = it.title + "!") }
                val titles = received().single { it.type == "conversations" }["items"]!!.jsonArray
                    .map { it.jsonObject.string("title") }
                assertEquals(listOf("Alpha!", "Beta!"), titles)
            }
        } finally {
            unmockkObject(DebugLog)
        }
    }

    // El test dedicado de appearance vive en WebUiMessagePresentationTest: aquí el display es un
    // mockk relaxed, así que el evento Display solo se verifica por presencia y orden.

    private fun sync(
        block: suspend TestScope.(send: suspend (String) -> Unit, received: () -> List<JsonObject>) -> Unit,
    ) = runTest {
        val sync = webUiSync(StandardTestDispatcher(testScheduler))
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch { sync.serve("login", incoming) { sent.send(it) } }
        runCurrent()
        block(
            { text -> incoming.send(text); runCurrent() },
            {
                runCurrent()
                generateSequence { sent.tryReceive().getOrNull() }
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .toList()
            },
        )
    }

    private fun webUiSync(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = WebUiSync(
        conversations = conversations,
        registry = registry,
        executionCoordinator = ConversationExecutionCoordinator(),
        hydration = hydration,
        customProviders = MutableStateFlow(emptyList()),
        display = flowOf(
            WebDisplayContext(
                resources = mockk(relaxed = true),
                toolCallDisplayMode = ToolCallDisplayModes.DEFAULT,
                thinkingSegmentDisplayMode = ThinkingSegmentDisplayModes.DEFAULT,
                autoExpandActiveGroup = true,
                parseInlineDollarMath = false,
            ),
        ),
        openChatSession = { session },
        projectionDispatcher = dispatcher,
    )

    private val JsonObject.type: String get() = string("type")

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.ids(): List<String> =
        this["messages"]!!.jsonArray.map { it.jsonObject.string("id") }

    private fun topology(id: String, parent: String?, participant: Participant, timestamp: Long) =
        MessageContextTopology(
            id = id,
            conversationId = "a",
            parentId = parent,
            status = MessageStatus.SUCCESS,
            participant = participant,
            timestamp = timestamp,
            modelName = null,
            runId = "run-$id",
            runSequence = timestamp,
            consumedAtPass = null,
        )

    private fun message(
        id: String,
        parent: String?,
        participant: Participant,
        text: String,
        status: MessageStatus = MessageStatus.SUCCESS,
    ) = ChatMessage(
        id = id,
        parentId = parent,
        text = text,
        participant = participant,
        status = status,
        timestamp = 10,
    )
}
