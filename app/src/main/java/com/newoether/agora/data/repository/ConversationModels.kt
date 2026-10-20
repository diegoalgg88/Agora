package com.newoether.agora.data.repository

/** Mutaciones de modelo jamás reemplazan campos ajenos de la conversación ni crean owners ausentes. */
suspend fun ConversationRepository.updateConversationModel(conversationId: String, modelId: String?): Boolean =
    chatDao.updateConversationModel(conversationId, modelId, System.currentTimeMillis()) == 1

suspend fun ConversationRepository.replaceConfiguredModelReferences(oldModelId: String, newModelId: String?) =
    chatDao.replaceConfiguredModelReferences(oldModelId, newModelId)

suspend fun ConversationRepository.renameConfiguredProviderModelReferences(oldProvider: String, newProvider: String) =
    chatDao.renameConfiguredProviderModelReferences(oldProvider, newProvider)
