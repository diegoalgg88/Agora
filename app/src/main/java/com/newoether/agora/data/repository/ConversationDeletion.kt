package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageAttachmentReference

internal suspend fun ConversationRepository.deleteConversationWithChildren(id: String) {
    prepareConversationDeletion(id)
    val scheduled = withSemanticTransaction(updatedAt = System.currentTimeMillis()) {
        chatDao.deleteOwnedConversationGraphs(id).fold(false) { accumulated, (raw, references) ->
            val draft = raw.decodeSelectedAttachments()
            accumulated or enqueueMessageAttachmentDebt(references) or when {
                raw != null && draft == null -> enqueueAttachmentReconcile()
                else -> enqueueAttachmentDebt(draft.orEmpty().reclaimablePaths())
            }
        }
    }
    // El fork agenda el debt worker inline; no existe scheduleMaintenance() como método.
    if (scheduled) scheduleMaintenance()
}

/** El caller es dueño de la transacción semántica y agenda el attachment debt antes de commitear. */
internal suspend fun ChatDao.deleteOwnedConversationGraphs(
    id: String,
): List<Pair<String?, List<MessageAttachmentReference>>> = (getSubagentIds(id) + id).mapNotNull { ownedId ->
    val conversation = getConversation(ownedId) ?: return@mapNotNull null
    val references = mutableListOf<MessageAttachmentReference>()
    var afterId: String? = null
    while (true) {
        val page = getConversationMessageAttachmentReferencesPage(ownedId, afterId, 128)
        references += page
        afterId = page.lastOrNull()?.id
        if (page.size < 128) break
    }
    deleteEmbeddingsByConversation(ownedId)
    deleteMessagesByConversation(ownedId)
    deleteConversation(ownedId)
    conversation.draftAttachments to references
}
