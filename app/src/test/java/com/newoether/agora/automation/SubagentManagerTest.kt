package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.SubagentOutboxRow
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.ConversationSettingsTransferCoordinator
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.Participant
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SubagentManager sobre el fixture DAO-isolated del fork (engine como lambda del ctor). */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentManagerTest {
    private class Fixture(scope: CoroutineScope) {
        val children = mutableMapOf("parent" to ChatEntity("parent", "Parent"))
        val messages = mutableMapOf<String, MessageEntity>()
        val dao = mockk<ChatDao>(relaxed = true)
        val repository = ConversationRepository(dao, null)
        val settings = mockk<SettingsRepository>(relaxed = true)
        val transfers = mockk<ConversationSettingsTransferCoordinator>()
        val engine = mockk<TaskExecutionEngine>()
        val registry = mockk<ConversationStateRegistry>(relaxed = true)
        val coordinator = ConversationExecutionCoordinator()
        val stoppedLoops = mutableListOf<String>()
        val identity = RunEffectIdentity("parent", 1L, "r-parent", 0, "effect")
        val context = GenerationContext(conversationId = "parent", toolEffectIdentity = identity,
            subagentToolsEnabled = true, enabledModelIds = listOf("Provider:model"), shellEnabled = true)
        val manager: SubagentManager
        init {
            coEvery { dao.getConversation(any()) } answers { children[firstArg()] }
            coEvery { dao.upsertConversation(any()) } answers {
                val row = firstArg<ChatEntity>()
                children[row.id] = row
            }
            coEvery { dao.isSubagentToolOwnerActive(any(), any(), any()) } answers {
                firstArg<String>() == "parent" && secondArg<String>() == "r-parent" && thirdArg<Int>() == 0
            }
            coEvery { dao.getSubagentIds(any()) } answers {
                children.values.filter { it.parentConversationId == firstArg<String>() }.map { it.id }
            }
            coEvery { dao.getLastMessageForConversation(any()) } answers { messages[firstArg()] }
            coEvery { dao.getLiveRun(any()) } returns null
            coEvery { dao.setSubagentResult(any(), any(), any(), any(), any(), any()) } answers {
                val row = children[firstArg<String>()]
                if (row == null || row.parentConversationId != secondArg<String>()) 0 else {
                    children[row.id] = row.copy(subagentResultJson = arg(2), subagentResultId = arg(3), subagentResultStatus = arg(4))
                    1
                }
            }
            coEvery { dao.pendingSubagentResults(any()) } answers {
                children.values.filter { it.parentConversationId == firstArg<String>() && it.subagentResultId != null &&
                    it.subagentResultId != it.subagentDeliveredResultId }.map {
                    SubagentOutboxRow(it.id, it.title, requireNotNull(it.subagentResultJson), requireNotNull(it.subagentResultId))
                }
            }
            every { registry.get(any()) } returns null
            every { settings.enabledModels } returns MutableStateFlow(setOf("Provider:model"))
            every { settings.conversationSettings } returns MutableStateFlow(emptyMap())
            coEvery { transfers.complete(any()) } returns true
            coEvery { engine.stopConversationAndAwait(any()) } returns Unit
            coEvery { engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                TaskExecutionEngine.Result.Success("answer", "Result")
            manager = SubagentManager(repository, settings, transfers, { engine }, registry, coordinator,
                AutomationExecutionGate(), scope, stopLoop = { stoppedLoops += it })
        }
        suspend fun spawn(name: String = "Worker") = manager.spawn(name, "Provider:model", "Task", context)
    }

    @Test fun creationDoesNotScanOwnersAndRejectsStaleNestedAndDisabledModels() = runTest {
        val f = Fixture(backgroundScope)
        coVerify(exactly = 0) { f.dao.getConversation(any()) }
        coVerify(exactly = 0) { f.dao.getSubagentIds(any()) }
        assertTrue(runCatching { f.manager.spawn("W", "disabled", "Task", f.context) }.isFailure)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task", f.context.copy(
            toolEffectIdentity = f.identity.copy(pass = 1))) }.isFailure)
        assertTrue(runCatching { f.manager.spawn("W", "Provider:model", "Task",
            f.context.copy(subagentToolsEnabled = false)) }.isFailure)
        assertEquals(setOf("parent"), f.children.keys)
    }

    @Test fun childrenRunWhileParentHoldsLeaseAndCompletionsMergeAfterItsRelease() = runTest {
        val f = Fixture(backgroundScope)
        val parentEntered = CompletableDeferred<Unit>()
        val releaseParent = CompletableDeferred<Unit>()
        val lease = backgroundScope.launch {
            f.coordinator.withAutomationConversationLock("parent") {
                parentEntered.complete(Unit)
                releaseParent.await()
            }
        }
        parentEntered.await()
        val first = f.spawn("One")
        val second = f.spawn("Two")
        runCurrent()
        assertEquals(setOf(first, second), f.manager.wait(listOf(first, second), 50L, f.context).map { it.first }.toSet())
        assertEquals("parent", f.children[first]?.parentConversationId)
        coVerify(exactly = 0) { f.engine.runOnceWithAutomationGuardsHeld("parent", any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        releaseParent.complete(Unit)
        lease.join()
        runCurrent()
        coVerify(exactly = 1) { f.engine.runOnceWithAutomationGuardsHeld("parent",
            match { it.contains("One") && it.contains("Two") }, any(), any(), any(), any(), "subagent",
            any(), match { it?.subagentResults?.keys == setOf(first, second) }, null) }
        coVerify(exactly = 1) { f.engine.runOnceWithAutomationGuardsHeld(first, "Task", "Provider:model", any(), any(), any(), "subagent", any(), any(), f.context) }
    }

    @Test fun stoppingWaitsForWorkerCancellationCleanupAndDoesNotStopOtherOwners() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cancelled.complete(Unit); cleanup.await() }
            }
        }
        val child = f.spawn()
        entered.await()
        val stop = backgroundScope.async { f.manager.stop(child, f.context) }
        cancelled.await()
        assertFalse(stop.isCompleted)
        cleanup.complete(Unit)
        stop.await()
        assertEquals("stopped", f.children[child]?.subagentResultStatus)
        coVerify(exactly = 1) { f.engine.stopConversationAndAwait(child) }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("parent") }
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("other") }
    }

    @Test fun followupFailureBeforeAdmissionCannotRepublishPreviousAnswer() = runTest {
        val f = Fixture(backgroundScope)
        f.children["child"] = ChatEntity("child", "Worker", parentConversationId = "parent", modelId = "Provider:model")
        f.messages["child"] = MessageEntity(id = "old", conversationId = "child", text = "Old answer",
            participant = Participant.MODEL, status = MessageStatus.SUCCESS, timestamp = 1L, runId = "old-run", runSequence = 0L)
        coEvery { f.engine.runOnceWithAutomationGuardsHeld(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            TaskExecutionEngine.Result.Failure("Provider not configured")
        f.manager.send("child", "New task", f.context)
        runCurrent()
        assertEquals("failed", f.children["child"]?.subagentResultStatus)
        val result = SubagentCompletion.decode(f.children["child"]?.subagentResultJson)
        assertEquals("Provider not configured", result?.text)
        assertNotEquals("old", result?.id)
    }

    @Test fun cancelledScopeDoesNotReadOwnersOrRearmDelivery() = runTest {
        val owner = SupervisorJob()
        val f = Fixture(CoroutineScope(backgroundScope.coroutineContext + owner))
        owner.cancel()
        repeat(3) {
            f.manager.conversationIdle("parent")
            f.manager.conversationOpened("parent")
        }
        runCurrent()
        coVerify(exactly = 0) { f.dao.getConversation(any()) }
        coVerify(exactly = 0) { f.dao.pendingSubagentResults(any()) }
    }

    @Test fun waitDoesNotConsumeReceiptBeforeToolRoundCommitAndRejectsForeignChildren() = runTest {
        val f = Fixture(backgroundScope)
        f.children["child"] = ChatEntity("child", "Worker", parentConversationId = "parent",
            subagentResultId = "done", subagentResultJson = SubagentCompletion.encode(SubagentCompletion("done", "completed", "Answer")))
        f.children["foreign"] = ChatEntity("foreign", "Other", parentConversationId = "other")
        assertEquals("done", f.manager.wait(listOf("child"), 1L, f.context).single().second.id)
        assertNull(f.children["child"]?.subagentDeliveredResultId)
        coVerify(exactly = 0) { f.dao.acknowledgeSubagentResult(any(), any(), any(), any()) }
        assertTrue(runCatching { f.manager.wait(listOf("foreign"), 1L, f.context) }.isFailure)
        assertTrue(runCatching { f.manager.stop("foreign", f.context) }.isFailure)
        coVerify(exactly = 0) { f.engine.stopConversationAndAwait("foreign") }
    }
}
