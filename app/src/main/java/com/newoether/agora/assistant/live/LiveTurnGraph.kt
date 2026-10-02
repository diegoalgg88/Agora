package com.newoether.agora.assistant.live

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.local.RunEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import java.util.UUID

/** One committed Live turn: a fresh terminal Run with its USER + MODEL rows and the branch
 *  selection edges that chain it onto the previous turn. */
internal data class LiveTurnGraph(
    val run: RunEntity,
    val userMessage: MessageEntity,
    val modelMessage: MessageEntity,
    val selectionUpdates: Map<String?, String>,
)

/**
 * Pure policy: shapes one finished logical Live turn into graph rows chained onto the previous
 * turn's MODEL row ([leafMessageId] / [leafRunId], both null for the first turn of a
 * conversation). No Android, Room or clock access — persistence stays in
 * [LiveVoiceSessionController] / `ChatDao.createCompletedRunWithMessages`. Every row belongs to
 * [conversationId]; the caller must only pass leaf ids that belong to that same conversation.
 */
internal fun buildLiveTurnGraph(
    conversationId: String,
    leafMessageId: String?,
    leafRunId: String?,
    spoken: String,
    replied: String,
    modelId: String,
    now: Long,
    newId: () -> String = { UUID.randomUUID().toString() },
): LiveTurnGraph {
    val runId = newId()
    val userMessage = MessageEntity(
        id = newId(),
        conversationId = conversationId,
        parentId = leafMessageId,
        text = spoken,
        status = MessageStatus.SUCCESS,
        participant = Participant.USER,
        timestamp = now,
        runId = runId,
        runSequence = 0,
        consumedAtPass = 0,
    )
    val modelMessage = MessageEntity(
        id = newId(),
        conversationId = conversationId,
        parentId = userMessage.id,
        text = replied,
        status = MessageStatus.SUCCESS,
        participant = Participant.MODEL,
        timestamp = now + 1,
        modelName = modelId,
        runId = runId,
        runSequence = 1,
    )
    val selectionUpdates = buildMap<String?, String> {
        leafMessageId?.let { put(it, userMessage.id) }
        put(userMessage.id, modelMessage.id)
    }
    val run = RunEntity(
        id = runId,
        conversationId = conversationId,
        parentRunId = leafRunId,
        status = RunStatus.COMPLETED,
        activeSlot = null,
        startedAt = userMessage.timestamp,
        lastCheckpointAt = modelMessage.timestamp,
        endedAt = modelMessage.timestamp,
        endReason = RunEndReason.MODEL_COMPLETED,
    )
    return LiveTurnGraph(run, userMessage, modelMessage, selectionUpdates)
}
