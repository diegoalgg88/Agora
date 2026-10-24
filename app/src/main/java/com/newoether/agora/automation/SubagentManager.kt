package com.newoether.agora.automation

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.*
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.model.SubagentSummary
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ConversationStateRegistry
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns delegation and the result outbox; the ordinary engine/mailbox still owns every Run. */
class SubagentManager(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val transfers: ConversationSettingsTransferCoordinator,
    private val engine: () -> TaskExecutionEngine,
    private val registry: ConversationStateRegistry,
    private val coordinator: ConversationExecutionCoordinator,
    private val gate: AutomationExecutionGate,
    private val scope: CoroutineScope,
    private val stopLoop: suspend (String) -> Unit = {},
) {
    init {
        conversations.onSubagentTerminal = ::conversationIdle
        conversations.prepareConversationDeletion = { id ->
            val children = conversations.getSubagentIds(id)
            val owned = children + id
            synchronized(deliveryMonitor) {
                owned.forEach { deliveries[it]?.cancel(); dirtyParents.remove(it) }
            }
            owned.forEach { child -> cancel(child); stopLoop(child); registry.remove(child) }
        }
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val deliveryMonitor = Any()
    private val deliveries = mutableMapOf<String, Job>()
    private val dirtyParents = mutableSetOf<String>()

    suspend fun spawn(name: String, modelId: String, task: String, ctx: GenerationContext): String {
        val identity = authorized(ctx)
        require(name.isNotBlank() && task.isNotBlank()) { "name and task are required" }
        require(modelId in ctx.enabledModelIds) { "model_id must be an enabled model ID" }
        settings.awaitInitialLoad()
        require(modelId in settings.enabledModels.value) { "The selected model is no longer enabled" }
        val parent = checkNotNull(conversations.getConversation(identity.conversationId))
        val id = UUID.randomUUID().toString()
        val child = ChatEntity(
            id = id, title = name.trim(), modelId = modelId,
            systemPromptId = parent.systemPromptId, origin = "subagent",
            parentConversationId = parent.id,
        )
        conversations.createSubagent(
            identity, child,
            settings.conversationSettings.value[parent.id]?.let { Json.encodeToString(it) },
        )
        launch(child, task, ctx)
        return id
    }

    suspend fun send(id: String, text: String, ctx: GenerationContext) {
        val identity = authorized(ctx)
        require(text.isNotBlank()) { "message is required" }
        val child = ownedChild(identity, id)
        check(jobs[id]?.isActive != true && conversations.getLiveRun(id) == null) {
            "Subagent is already running; wait or stop it before sending another task"
        }
        launch(child, text, ctx)
    }

    private fun launch(child: ChatEntity, task: String, ctx: GenerationContext) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var completion: SubagentCompletion? = null
            var previousMessageId: String? = null
            try {
                gate.withExecution {
                    coordinator.withAutomationConversationLock(child.id) {
                        transfers.complete(child.id)
                        conversations.recoverConversationRuntime(child.id)
                        previousMessageId = conversations.getLastMessageForConversation(child.id)?.id
                        val result = engine().runOnceWithAutomationGuardsHeld(
                            conversationId = child.id, userText = task, modelId = child.modelId,
                            requestKind = "subagent", contextOverride = ctx,
                        )
                        completion = terminalCompletion(child.id, previousMessageId) ?: when (result) {
                            is TaskExecutionEngine.Result.Success -> SubagentCompletion(
                                result.modelMessageId, "completed", result.text, result.modelMessageId,
                            )
                            is TaskExecutionEngine.Result.Failure -> SubagentCompletion(
                                UUID.randomUUID().toString(), "failed", result.reason,
                            )
                            is TaskExecutionEngine.Result.Busy -> null
                        }
                        completion?.let {
                            conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), it)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    completion = terminalCompletion(child.id, previousMessageId, stopped = true)
                    completion?.let {
                        conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), it)
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                completion = SubagentCompletion(UUID.randomUUID().toString(), "failed", error.message ?: "Subagent failed")
                conversations.recordSubagentCompletion(child.id, checkNotNull(child.parentConversationId), checkNotNull(completion))
            } finally {
                if (completion != null) requestDelivery(checkNotNull(child.parentConversationId))
            }
        }
        check(jobs.putIfAbsent(child.id, job) == null) {
            job.cancel()
            "Subagent is already running"
        }
        job.invokeOnCompletion { jobs.remove(child.id, job) }
        job.start()
    }

    suspend fun list(ctx: GenerationContext): List<SubagentSummary> {
        val identity = authorized(ctx)
        return conversations.observeSubagents(identity.conversationId).first().map {
            if (jobs[it.id]?.isActive == true) it.copy(status = "running") else it
        }
    }

    suspend fun wait(ids: List<String>, timeoutMs: Long, ctx: GenerationContext): List<Pair<String, SubagentCompletion>> {
        val identity = authorized(ctx)
        val selected = ids.ifEmpty { conversations.getSubagentIds(identity.conversationId) }.distinct()
        selected.forEach { ownedChild(identity, it) }
        withTimeoutOrNull(timeoutMs.coerceIn(1, 300_000)) {
            selected.forEach { id ->
                jobs[id]?.join()
                registry.get(id)?.awaitSendAvailable()
            }
        }
        authorized(ctx)
        val results = conversations.pendingSubagentResults(identity.conversationId)
            .filter { it.id in selected && jobs[it.id]?.isActive != true }
            .map { it.id to checkNotNull(SubagentCompletion.decode(it.resultJson)) }
        // Receipts are acknowledged only when the ordinary tool round commits its result rows.
        return results
    }

    suspend fun stop(id: String, ctx: GenerationContext) {
        val identity = authorized(ctx)
        ownedChild(identity, id)
        cancel(id)
        authorized(ctx)
    }

    suspend fun cancel(id: String) {
        val job = jobs[id]
        job?.cancel()
        engine().stopConversationAndAwait(id)
        job?.join()
    }

    /** Only the explicitly opened owner or a process-admitted idle event may activate delivery. */
    fun conversationOpened(id: String) = requestDelivery(id)

    fun conversationIdle(id: String) {
        if (!scope.isActive) return
        scope.launch {
            if (jobs[id] != null) return@launch
            val child = conversations.getConversation(id) ?: return@launch
            val parent = child.parentConversationId
            if (parent == null) {
                requestDelivery(id)
            } else if (child.subagentResultId != null && child.subagentResultId != child.subagentDeliveredResultId) {
                requestDelivery(parent)
            }
        }
    }

    private suspend fun terminalCompletion(
        id: String, previousMessageId: String?, stopped: Boolean = false,
    ): SubagentCompletion? {
        if (conversations.getLiveRun(id) != null) return null
        val message = conversations.getLastMessageForConversation(id)
        if (message?.participant != Participant.MODEL || message.id == previousMessageId) {
            return if (stopped) SubagentCompletion(UUID.randomUUID().toString(), "stopped", "Subagent stopped") else null
        }
        val status = when (message.status) {
            MessageStatus.SUCCESS -> "completed"
            MessageStatus.STOPPED -> "stopped"
            MessageStatus.ERROR -> "failed"
            else -> return null
        }
        return SubagentCompletion(message.id, status, message.text, message.id)
    }

    private fun requestDelivery(parentId: String) {
        synchronized(deliveryMonitor) {
            if (!scope.isActive) return
            dirtyParents += parentId
            if (parentId in deliveries) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (synchronized(deliveryMonitor) { dirtyParents.remove(parentId) }) {
                        deliver(parentId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.w("SubagentManager", "Result delivery deferred for $parentId", error)
                }
            }
            deliveries[parentId] = job
            job.invokeOnCompletion {
                synchronized(deliveryMonitor) {
                    deliveries.remove(parentId, job)
                    if (scope.isActive && parentId in dirtyParents) requestDelivery(parentId)
                }
            }
            job.start()
        }
    }

    private suspend fun deliver(parentId: String) {
        registry.get(parentId)?.awaitSendAvailable()
        gate.withExecution {
            coordinator.withAutomationConversationLock(parentId) {
                registry.get(parentId)?.awaitSendAvailable()
                val pending = conversations.pendingSubagentResults(parentId)
                if (pending.isEmpty() || conversations.getConversation(parentId) == null) return@withAutomationConversationLock
                val receipts = pending.associate { it.id to it.resultId }
                val text = pending.joinToString("\n\n") {
                    val result = checkNotNull(SubagentCompletion.decode(it.resultJson))
                    "${it.title} (${it.id}) [${result.status}]\n${result.text}"
                }
                engine().runOnceWithAutomationGuardsHeld(
                    conversationId = parentId, userText = text, requestKind = "subagent",
                    source = MessageSource(MessageSource.Kind.SUBAGENT, subagentResults = receipts),
                )
            }
        }
    }

    private suspend fun authorized(ctx: GenerationContext): RunEffectIdentity {
        check(ctx.subagentToolsEnabled) { "Subagent tools are unavailable in this context" }
        val identity = checkNotNull(ctx.toolEffectIdentity) { "An active tool effect is required" }
        check(identity.conversationId == ctx.conversationId)
        conversations.requireSubagentToolOwner(identity)
        return identity
    }

    private suspend fun ownedChild(identity: RunEffectIdentity, id: String): ChatEntity =
        checkNotNull(conversations.getConversation(id)?.takeIf { it.parentConversationId == identity.conversationId }) {
            "Subagent does not belong to the current conversation"
        }
}
