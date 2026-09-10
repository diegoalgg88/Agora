package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatEntity

private const val HEARTBEAT_ORIGIN = "heartbeat"
private const val HEARTBEAT_CONVERSATION_TITLE = "Heartbeat"

/**
 * Returns the single dedicated heartbeat conversation, creating it on first use.
 *
 * Lookup order: newest conversation with origin "heartbeat", then the newest legacy
 * "Heartbeat"-titled conversation (created by the pre-origin scheduler) which is adopted
 * by marking its origin. Every heartbeat run reuses this id so history accumulates in one
 * conversation instead of spawning a fresh one per run.
 */
suspend fun ConversationRepository.getOrCreateHeartbeatConversationId(
    modelId: String? = null,
): String {
    getConversationByOrigin(HEARTBEAT_ORIGIN)?.let { return it.id }
    getConversationByTitle(HEARTBEAT_CONVERSATION_TITLE)?.let { legacy ->
        upsertConversation(legacy.copy(origin = HEARTBEAT_ORIGIN))
        return legacy.id
    }
    val id = java.util.UUID.randomUUID().toString()
    upsertConversation(
        ChatEntity(
            id = id,
            title = HEARTBEAT_CONVERSATION_TITLE,
            modelId = modelId,
            origin = HEARTBEAT_ORIGIN,
        )
    )
    return id
}