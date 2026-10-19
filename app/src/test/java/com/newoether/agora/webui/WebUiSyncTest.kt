package com.newoether.agora.webui

import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.local.MessageContextTopology
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.viewmodel.ConversationGenerationSnapshot
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
        every { getAllConversations() } returns list
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

    @Test
    fun listThenOpenSendsTheSavedBranchAfterRecovery() = sync { send, received ->
        val listed = received().single { it.type == "conversations" }
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

    private fun sync(
        block: suspend TestScope.(send: suspend (String) -> Unit, received: () -> List<JsonObject>) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sync = WebUiSync(
            conversations = conversations,
            registry = registry,
            executionCoordinator = ConversationExecutionCoordinator(),
            hydration = hydration,
            customProviders = MutableStateFlow(emptyList()),
            parseInlineDollarMath = MutableStateFlow(false),
            projectionDispatcher = dispatcher,
        )
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch { sync.serve(incoming) { sent.send(it) } }
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
