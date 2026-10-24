package com.newoether.agora.data.local

import androidx.room.Query
import com.newoether.agora.model.SubagentSummary
import kotlinx.coroutines.flow.Flow

data class SubagentOutboxRow(
    val id: String,
    val title: String,
    val resultJson: String,
    val resultId: String,
)

/** Indexed parent-scoped reads. No startup enumeration or full child payload observation. */
interface ChatSubagentDao {
    @Query("""
        SELECT id, title, subagentResultJson AS resultJson, subagentResultId AS resultId
        FROM conversations WHERE parentConversationId = :parentId
            AND subagentResultJson IS NOT NULL AND subagentResultId IS NOT NULL
            AND (subagentDeliveredResultId IS NULL OR subagentDeliveredResultId != subagentResultId)
            AND NOT EXISTS(SELECT 1 FROM runs WHERE conversationId = conversations.id AND activeSlot = 1)
        ORDER BY lastUpdated, id
    """)
    suspend fun pendingSubagentResults(parentId: String): List<SubagentOutboxRow>

    @Query("SELECT parentConversationId FROM conversations WHERE id = :id")
    fun observeParentConversationId(id: String): Flow<String?>

    @Query("SELECT id FROM conversations WHERE parentConversationId = :parentId ORDER BY lastUpdated, id")
    suspend fun getSubagentIds(parentId: String): List<String>

    @Query("""
        SELECT c.id, c.title, c.modelId, c.parentConversationId,
            CASE WHEN EXISTS(SELECT 1 FROM runs r WHERE r.conversationId = c.id AND r.activeSlot = 1)
                THEN 'running' ELSE COALESCE(c.subagentResultStatus, 'idle') END AS status
        FROM conversations c WHERE c.parentConversationId = :parentId ORDER BY c.lastUpdated, c.id
    """)
    fun observeSubagents(parentId: String): Flow<List<SubagentSummary>>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM runs r JOIN conversations c ON c.id = r.conversationId
            WHERE c.id = :conversationId AND c.parentConversationId IS NULL
            AND r.id = :runId AND r.status = 'ACTIVE' AND r.activeSlot = 1
            AND r.currentPass = :pass AND r.stopRequestedAt IS NULL)
    """)
    suspend fun isSubagentToolOwnerActive(conversationId: String, runId: String, pass: Int): Boolean

    @Query("""
        UPDATE conversations SET subagentResultJson = :result, subagentResultId = :resultId,
            subagentResultStatus = :status, dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :id AND parentConversationId = :parentId
            AND NOT EXISTS(SELECT 1 FROM runs WHERE conversationId = :id AND activeSlot = 1)
    """)
    suspend fun setSubagentResult(
        id: String, parentId: String, result: String, resultId: String, status: String, at: Long,
    ): Int

    @Query("""
        UPDATE conversations SET subagentDeliveredResultId = :resultId,
            dataChangedAt = MAX(dataChangedAt + 1, :at)
        WHERE id = :id AND parentConversationId = :parentId AND subagentResultId = :resultId
            AND (subagentDeliveredResultId IS NULL OR subagentDeliveredResultId != :resultId)
    """)
    suspend fun acknowledgeSubagentResult(id: String, parentId: String, resultId: String, at: Long): Int
}
