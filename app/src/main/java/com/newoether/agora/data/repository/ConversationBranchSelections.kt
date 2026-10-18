package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Branch selections de una conversación (mensajes y runs). Extraído de ConversationRepository:
 * ese archivo llegó al límite físico de 999 líneas con el watermark dataChangedAt del backup v5,
 * y la clase interna del upstream siempre fue el owner natural de estas rutas.
 */
internal class ConversationBranchSelections(private val chatDao: ChatDao) {

    suspend fun saveMessageSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val json = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedBranchesJson != json) {
            check(
                chatDao.updateMessageBranchSelections(
                    conversationId = conversationId,
                    selectedBranchesJson = json,
                    at = System.currentTimeMillis(),
                ) == 1
            ) { "Conversation $conversationId disappeared during message branch selection" }
        }
    }

    suspend fun restoreMessageSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId)
            ?: return@withContext emptyMap()
        val raw = conversation.selectedBranchesJson ?: return@withContext emptyMap()
        try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveRunSelections(
        conversationId: String,
        selections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val conversation = chatDao.getConversation(conversationId) ?: return@withContext
        val stored = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedRunBranchesJson != stored) {
            check(
                chatDao.updateRunBranchSelections(
                    conversationId = conversationId,
                    selectedRunBranchesJson = stored,
                    at = System.currentTimeMillis(),
                ) == 1
            ) { "Conversation $conversationId disappeared during Run branch selection" }
        }
    }

    suspend fun restoreRunSelections(
        conversationId: String,
    ): Map<String?, String> = withContext(Dispatchers.Default) {
        val raw = chatDao.getConversation(conversationId)?.selectedRunBranchesJson
            ?: return@withContext emptyMap()
        runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
                .mapKeys { if (it.key == "null") null else it.key }
        }.getOrDefault(emptyMap())
    }

    /** Persist Run and legacy message selection maps in the same row update. */
    suspend fun selectRunBranch(
        conversationId: String,
        parentRunId: String?,
        runId: String,
        messageSelections: Map<String?, String>,
    ) = withContext(Dispatchers.Default) {
        val runSelections = restoreRunSelections(conversationId).toMutableMap()
        runSelections[parentRunId] = runId
        check(
            chatDao.updateBranchSelections(
                conversationId = conversationId,
                selectedBranchesJson = Json.encodeToString(messageSelections.mapKeys { it.key ?: "null" }),
                selectedRunBranchesJson = Json.encodeToString(runSelections.mapKeys { it.key ?: "null" }),
                at = System.currentTimeMillis(),
            ) == 1
        ) { "Conversation $conversationId disappeared during branch selection" }
    }
}
