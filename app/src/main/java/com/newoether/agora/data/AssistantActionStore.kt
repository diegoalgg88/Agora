package com.newoether.agora.data

import androidx.room.withTransaction
import com.newoether.agora.data.local.AssistantActionStatus
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.tool.AssistantDeviceToolProvider
import com.newoether.agora.tool.parseAssistantActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Store for assistant device-actions (alarms, calendar writes) staged by the AI and
 * awaiting the user's approval.
 *
 * Room is the single durable truth, mirroring [SmsDraftStore]: the action list is a Room
 * flow, the cap (20) is enforced by evicting the oldest rows atomically on insert.
 * Execution is orchestrated here: PENDING → EXECUTING → DONE/FAILED, where the actual
 * device dispatch goes through [AssistantDeviceToolProvider] — never from the AI, always
 * from the user's tap in the approval banner.
 */
class AssistantActionStore(
    private val chatDao: ChatDao,
    private val database: ChatDatabase,
) {

    /** Current actions, newest first, capped at 20. */
    val actions: Flow<List<AssistantAction>> = chatDao.getAssistantActionsFlow().map { entities ->
        entities.map { it.toData() }
    }

    /** Adds a new staged action; oldest actions beyond the cap are evicted atomically. */
    suspend fun addAction(action: AssistantAction) = withContext(Dispatchers.IO) {
        database.withTransaction {
            chatDao.upsertAssistantAction(action.toEntity())
            chatDao.deleteAssistantActionsBeyondCap(MAX_ACTIONS)
        }
    }

    suspend fun removeAction(actionId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteAssistantAction(actionId)
    }

    /**
     * User-triggered execution: flips the action to EXECUTING, dispatches via [provider]'s
     * approved path, then records DONE or FAILED (with the failure reason). Returns false
     * if the action is missing or no longer PENDING.
     */
    suspend fun approveAction(actionId: String, provider: AssistantDeviceToolProvider): Boolean {
        val action = actions.first().find { it.id == actionId } ?: return false
        if (action.status != AssistantActionStatus.PENDING) return false
        updateStatus(actionId, AssistantActionStatus.EXECUTING)
        val result = provider.runApprovedAction(action.type, action.argumentsJson)
        val (succeeded, error) = parseAssistantActionResult(result)
        return if (succeeded) {
            chatDao.deleteAssistantAction(actionId)
            true
        } else {
            updateStatus(actionId, AssistantActionStatus.FAILED, error)
            false
        }
    }

    /** Removes non-pending actions older than [cutoffEpochMs] (7 days). */
    suspend fun cleanupOldActions(cutoffEpochMs: Long = System.currentTimeMillis() - CLEANUP_AGE_MS) =
        withContext(Dispatchers.IO) {
            chatDao.cleanupOldAssistantActions(cutoffEpochMs)
        }

    private suspend fun updateStatus(actionId: String, status: AssistantActionStatus, error: String? = null) =
        withContext(Dispatchers.IO) {
            chatDao.updateAssistantActionStatus(actionId, status, error)
        }

    private fun AssistantAction.toEntity() = com.newoether.agora.data.local.AssistantActionEntity(
        id = id,
        type = type,
        argumentsJson = argumentsJson,
        summary = summary,
        createdAtEpochMs = createdAtEpochMs,
        status = status,
        lastError = lastError,
    )

    private fun com.newoether.agora.data.local.AssistantActionEntity.toData() = AssistantAction(
        id = id,
        type = type,
        argumentsJson = argumentsJson,
        summary = summary,
        createdAtEpochMs = createdAtEpochMs,
        status = status,
        lastError = lastError,
    )

    companion object {
        private const val MAX_ACTIONS = 20
        private const val CLEANUP_AGE_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

/**
 * A device-action the AI has staged. Nothing runs until the user taps Approve in the
 * review banner — the existence of a staged action is the defensive gate between AI
 * intent and real-world effect.
 */
data class AssistantAction(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String,
    val argumentsJson: String,
    val summary: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val status: AssistantActionStatus = AssistantActionStatus.PENDING,
    val lastError: String? = null,
)
