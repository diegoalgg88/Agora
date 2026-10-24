package com.newoether.agora.webui

import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.thinkingCapabilityForSelectedModel
import com.newoether.agora.model.ContextBudget
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.OpenAiServiceTiers
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.ThinkingResolution
import com.newoether.agora.ui.chat.EffectiveConversationControls
import com.newoether.agora.ui.chat.advancedMaxTokensPresets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Proyección JSON de los controls/tools del composer del navegador. El navegador nunca copia la
 * política de modelos/proveedores: este archivo resuelve la capability del modelo activo y proyecta
 * solo los valores que el endpoint acepta (displayed*), igual que el panel nativo del teléfono.
 */
internal fun projectComposerControls(
    state: WebUiChatSession.ComposerState,
    customProviders: List<CustomProviderConfig>,
): JsonObject? = state.controls?.let { controls ->
    val model = ModelId.parse(state.modelId)
    val capability = thinkingCapabilityForSelectedModel(state.modelId, customProviders)
    val thinking = ThinkingResolution.resolve(capability, controls.thinkingEnabled,
        controls.thinkingLevel, controls.thinkingBudgetEnabled, controls.thinkingBudgetTokens)
    buildJsonObject {
        put("isGemini", state.modelValid && providerDisplayName(model.providerName, customProviders).equals("google", ignoreCase = true))
        put("thinkingCanDisable", capability.canDisableThinking)
        put("thinkingSupportsBudget", capability.supportsThinkingBudget)
        put("thinkingEfforts", JsonArray(capability.supportedEfforts.map(::JsonPrimitive)))
        put("thinkingBudgetPresets", JsonArray(ThinkingLevels.budgetPresets.map(::JsonPrimitive)))
        put("displayedThinkingEnabled", thinking.enabled)
        put("displayedThinkingLevel", thinking.effort ?: capability.nearestEffort(controls.thinkingLevel) ?: controls.thinkingLevel)
        put("displayedThinkingBudgetEnabled", thinking.budgetTokens != null)
        put("displayedThinkingBudgetTokens", thinking.budgetTokens ?: controls.thinkingBudgetTokens)
        // El fork mantiene el catálogo plano de 7 tiers de su UI nativa; el normalize del repo
        // decide qué valor persiste, y la proyección no filtra por modelo.
        put("serviceTiers", JsonArray(OpenAiServiceTiers.values.map(::JsonPrimitive)))
        put("displayedServiceTier", OpenAiServiceTiers.normalize(controls.openAiServiceTierState.tier))
        put("codeExecutionEnabled", controls.codeExecutionEnabled)
        put("googleSearchEnabled", controls.googleSearchEnabled)
        put("thinkingEnabled", controls.thinkingEnabled)
        put("thinkingLevel", controls.thinkingLevel)
        put("thinkingBudgetEnabled", controls.thinkingBudgetEnabled)
        put("thinkingBudgetTokens", controls.thinkingBudgetTokens)
        put("openAiWebSearchAvailable", controls.openAiWebSearchAvailable)
        put("openAiWebSearchEnabled", controls.openAiWebSearchEnabled)
        put("openAiServiceTierAvailable", controls.openAiServiceTierState.available)
        put("openAiServiceTierEnabled", controls.openAiServiceTierState.enabled)
        put("openAiServiceTier", controls.openAiServiceTierState.tier)
        put("webSearchAvailable", controls.webSearchAvailable)
        put("webSearchEnabled", controls.webSearchEnabled)
        put("shellAvailable", controls.shellAvailable)
        put("shellEnabled", controls.shellEnabled)
        put("showLowContextMode", controls.showLowContextMode)
        put("lowContextModeEnabled", controls.lowContextModeEnabled)
        put("contextWindow", controls.contextWindow)
    }
}

/** Bloque `advanced`: overrides/defaults serializados + presets de contexto y max tokens. */
internal fun projectComposerAdvanced(
    state: WebUiChatSession.ComposerState,
    json: kotlinx.serialization.json.Json,
): JsonObject = buildJsonObject {
    put("overrides", json.encodeToJsonElement(ConversationSettings.serializer(), state.generationParameters.copy(
        contextWindow = state.generationParameters.contextWindow?.let(ContextBudget::normalize))))
    put("defaults", json.encodeToJsonElement(ConversationSettings.serializer(), state.generationDefaults.copy(
        contextWindow = state.generationDefaults.contextWindow?.let(ContextBudget::normalize))))
    put("contextPresets", JsonArray(ContextBudget.PRESETS.map(::JsonPrimitive)))
    put("contextLabels", JsonArray(ContextBudget.PRESETS.map { value -> JsonPrimitive(ContextBudget.compactLabel(value)) }))
    state.generationParameters.contextWindow?.let { value ->
        put("contextOverrideLabel", ContextBudget.compactLabel(ContextBudget.normalize(value)))
    }
    put("contextDefaultLabel", JsonPrimitive(ContextBudget.compactLabel(ContextBudget.normalize(state.generationDefaults.contextWindow))))
    put("maxTokensPresets", JsonArray(advancedMaxTokensPresets.map(::JsonPrimitive)))
}

/** Bloque `compact`: configuración actual del editor manual y si un compact ya corre. */
internal fun projectComposerCompact(state: WebUiChatSession.ComposerState): JsonObject? =
    state.compactDefaults?.let { request -> buildJsonObject {
        put("modelId", request.model)
        put("prompt", request.prompt)
        put("retainCount", request.retainLogicalMessages)
        put("compacting", state.compacting)
    } }

/** Cola de guidance y attachments del draft, proyectados como en el teléfono. */
private fun projectQueued(queued: com.newoether.agora.viewmodel.QueuedSend): JsonObject = buildJsonObject {
    put("id", queued.id)
    put("text", queued.text)
    put("attachmentCount", queued.attachments.size)
}

private fun projectAttachment(attachment: com.newoether.agora.model.SelectedAttachment, progress: Pair<Int, Int>?): JsonObject =
    buildJsonObject {
        put("id", attachment.localId)
        put("type", attachment.type)
        put("name", attachment.fileName)
        put("state", attachment.importState.name)
        put("storage", attachment.storage.name)
        put("unavailable", attachment.unavailable)
        put("pageCount", attachment.pageCount)
        put("durationMs", attachment.videoDurationMs)
        put("frameCount", attachment.frameCount)
        put("intervalMs", attachment.sliceIntervalMs)
        put("staged", attachment.localPath != null)
        if (attachment.type == "file" && attachment.importState == com.newoether.agora.model.AttachmentImportState.READY &&
            attachment.storage == com.newoether.agora.model.AttachmentStorage.APP_PRIVATE && !attachment.unavailable) {
            put("text", attachment.preparedText)
        }
        attachment.selectedPages?.let { pages ->
            put("selectedPages", JsonArray(pages.sorted().map(::JsonPrimitive)))
        }
        put("pagePreviewCount", attachment.preRenderedPaths?.size ?: 0)
        put("framePreviewCount", attachment.processedFrames?.size ?: 0)
        attachment.videoDurationMs?.let { duration ->
            put("defaultFrameCount", com.newoether.agora.ui.chat.VideoSliceDefaults.defaultFrameCount(duration))
        }
        progress?.let { (done, total) ->
            put("previewDone", done)
            put("previewTotal", total)
        }
    }

/** El evento Composer completo, ensamblado desde la proyección de sus bloques. */
internal fun projectComposerEvent(
    state: WebUiChatSession.ComposerState,
    customProviders: List<CustomProviderConfig>,
    json: Json,
): WebSyncEvent.Composer = WebSyncEvent.Composer(
    state.conversationId, state.snapshot.phase.name, state.snapshot.acceptedVersion,
    state.seq, state.text, state.editRevision, state.actionId, state.modelValid,
    state.generating, state.stopping,
    state.modelId, state.models, state.queue.map(::projectQueued),
    state.attachments.map { projectAttachment(it, state.pdfProgress[it.localId]) },
    projectComposerControls(state, customProviders),
    projectComposerAdvanced(state, json),
    projectComposerCompact(state),
)
