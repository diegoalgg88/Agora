package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.ConversationSettingsTransferEntity
import com.newoether.agora.model.MessageSource
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.SubagentCompletion
import com.newoether.agora.model.SubagentSummary
import kotlinx.coroutines.flow.Flow

suspend fun ConversationRepository.pendingSubagentResults(parentId: String) =
    chatDao.pendingSubagentResults(parentId)

fun ConversationRepository.observeSubagents(parentId: String): Flow<List<SubagentSummary>> =
    chatDao.observeSubagents(parentId)
fun ConversationRepository.observeParentConversationId(id: String): Flow<String?> =
    chatDao.observeParentConversationId(id)
suspend fun ConversationRepository.getSubagentIds(parentId: String): List<String> =
    chatDao.getSubagentIds(parentId)

suspend fun ConversationRepository.requireSubagentToolOwner(identity: RunEffectIdentity) {
    check(chatDao.isSubagentToolOwnerActive(identity.conversationId, identity.runId, identity.pass)) {
        "Subagent tools require the exact active parent Run and pass"
    }
}

/** Child creation and parent authorization share one Room transaction. */
suspend fun ConversationRepository.createSubagent(
    identity: RunEffectIdentity, child: ChatEntity, settingsJson: String?,
) = withProviderContextSnapshot {
    requireSubagentToolOwner(identity)
    require(child.parentConversationId == identity.conversationId)
    check(chatDao.getConversation(child.id) == null)
    chatDao.upsertConversation(child)
    chatDao.upsertConversationSettingsTransfer(ConversationSettingsTransferEntity(child.id, settingsJson))
}

suspend fun ConversationRepository.recordSubagentCompletion(
    childId: String, parentId: String, completion: SubagentCompletion,
): Boolean = chatDao.setSubagentResult(
    childId, parentId, SubagentCompletion.encode(completion), completion.id, completion.status,
    System.currentTimeMillis(),
) == 1

suspend fun ConversationRepository.consumeSubagentCompletions(
    identity: RunEffectIdentity, receipts: Map<String, String>,
) = withProviderContextSnapshot {
    requireSubagentToolOwner(identity)
    receipts.forEach { (id, resultId) ->
        check(chatDao.acknowledgeSubagentResult(id, identity.conversationId, resultId, System.currentTimeMillis()) == 1) {
            "Subagent result was already consumed or replaced"
        }
    }
}

internal suspend fun ConversationRepository.persistSubagentTerminal(childId: String, at: Long) {
    val parentId = chatDao.getConversation(childId)?.parentConversationId ?: return
    val message = chatDao.getLastMessageForConversation(childId) ?: return
    if (message.participant != com.newoether.agora.model.Participant.MODEL) return
    val status = when (message.status) {
        com.newoether.agora.model.MessageStatus.SUCCESS -> "completed"
        com.newoether.agora.model.MessageStatus.STOPPED -> "stopped"
        com.newoether.agora.model.MessageStatus.ERROR -> "failed"
        else -> return
    }
    val completion = SubagentCompletion(message.id, status, message.text, message.id)
    chatDao.setSubagentResult(childId, parentId, SubagentCompletion.encode(completion), completion.id, status, at)
}

/** Retorna los receipts confirmados para que el caller dispare el hook fuera de la transacción. */
internal suspend fun ConversationRepository.commitSubagentWaitReceipts(
    messages: List<com.newoether.agora.data.local.MessageEntity>,
): Map<String, String> {
    val acknowledged = linkedMapOf<String, String>()
    messages.forEach { message ->
        val raw = message.toolCallJson ?: return@forEach
        if (message.participant != com.newoether.agora.model.Participant.USER) return@forEach
        val call = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<com.newoether.agora.model.MessageSegment>>(raw)
        }.getOrNull()?.singleOrNull { it.type == "tool" && it.toolName == "wait_agents" } ?: return@forEach
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(call.toolResult)) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return@forEach
        val receipts = root["subagent_receipts"] as? kotlinx.serialization.json.JsonObject ?: return@forEach
        receipts.forEach { (id, value) ->
            val resultId = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("Invalid result receipt")
            // Duplicate wait calls in one tool batch are idempotent; replacement still fails closed.
            val child = checkNotNull(chatDao.getConversation(id))
            check(child.parentConversationId == message.conversationId && child.subagentResultId == resultId)
            if (child.subagentDeliveredResultId != resultId) {
                check(chatDao.acknowledgeSubagentResult(id, message.conversationId, resultId, System.currentTimeMillis()) == 1)
            }
            acknowledged[id] = resultId
        }
    }
    return acknowledged
}

/** Un send descartado consume sus receipts exactos: el parent no vuelve a ofrecerlos. */
suspend fun ConversationRepository.dismissSubagentResults(
    parentId: String, receipts: Map<String, String>,
) {
    receipts.forEach { (id, resultId) ->
        chatDao.acknowledgeSubagentResult(id, parentId, resultId, System.currentTimeMillis())
    }
}

/** Called only inside the accepted-input graph transaction; mismatch rolls the graph back. */
internal suspend fun ConversationRepository.commitSubagentReceipts(
    parentId: String, source: MessageSource?, at: Long,
) {
    if (source?.kind != MessageSource.Kind.SUBAGENT) return
    source.subagentResults.forEach { (id, resultId) ->
        check(chatDao.acknowledgeSubagentResult(id, parentId, resultId, at) == 1) {
            "Subagent result was already consumed or replaced"
        }
    }
}
