package com.newoether.agora.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Media settings (image generation + image transcription) flows and savers.
 * Separated from SettingsManager to keep file sizes under the 999-line limit.
 * Shares the same settings DataStore and preference keys — SettingsManager
 * delegates to this store without changing call sites.
 */
class MediaSettingsStore(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
) {

    // ── Image generation ─────────────────────────────────────────────

    val imageGenEnabled: Flow<Boolean> = dataStore.data.map { it[IMAGE_GEN_ENABLED] ?: false }
    // Selected image model "Provider:modelId" (null = none chosen). Creds reused from that provider.
    val imageGenModel: Flow<String?> = dataStore.data.map { it[IMAGE_GEN_MODEL] }
    val imageGenSize: Flow<String> = dataStore.data.map { it[IMAGE_GEN_SIZE] ?: "1024x1024" }
    val imageGenBackend: Flow<String> = dataStore.data.map {
        normalizeImageGenBackend(it[IMAGE_GEN_BACKEND])
    }
    val aiHordeImageModel: Flow<String> = dataStore.data.map { it[AI_HORDE_IMAGE_MODEL] ?: "" }
    val aiHordeImageApiKey: Flow<String> = dataStore.data.map {
        SecretCrypto.decrypt(it[AI_HORDE_IMAGE_API_KEY] ?: "")
    }

    suspend fun saveImageGenEnabled(enabled: Boolean) {
        dataStore.edit { it[IMAGE_GEN_ENABLED] = enabled }
    }
    suspend fun saveImageGenModel(model: String?) {
        dataStore.edit {
            if (model == null) it.remove(IMAGE_GEN_MODEL) else it[IMAGE_GEN_MODEL] = model
        }
    }
    suspend fun saveImageGenSize(size: String) {
        dataStore.edit { it[IMAGE_GEN_SIZE] = size }
    }
    suspend fun saveImageGenBackend(backend: String) {
        dataStore.edit { it[IMAGE_GEN_BACKEND] = normalizeImageGenBackend(backend) }
    }
    suspend fun saveAiHordeImageModel(model: String) {
        dataStore.edit { it[AI_HORDE_IMAGE_MODEL] = model.trim() }
    }
    suspend fun saveAiHordeImageApiKey(key: String) {
        dataStore.edit {
            if (key.isBlank()) it.remove(AI_HORDE_IMAGE_API_KEY)
            else it[AI_HORDE_IMAGE_API_KEY] = SecretCrypto.encrypt(key)
        }
    }

    // ── Image transcription ──────────────────────────────────────────

    val imageTranscriptionEnabled: Flow<Boolean> = dataStore.data.map {
        it[IMAGE_TRANSCRIPTION_ENABLED] ?: true
    }
    val imageTranscriptionEnabledModels: Flow<Set<String>> =
        dataStore.data.map { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] ?: emptySet() }
    val imageTranscriptionModel: Flow<String?> = dataStore.data.map { it[IMAGE_TRANSCRIPTION_MODEL] }
    val imageTranscriptionBatchSize: Flow<Int> =
        dataStore.data.map { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] ?: 3 }
    val imageTranscriptionPrompt: Flow<String> = dataStore.data.map { pref ->
        pref[IMAGE_TRANSCRIPTION_PROMPT]?.takeIf { it.isNotBlank() } ?: BuiltInPrompts.IMAGE_TRANSCRIPTION_USER
    }

    suspend fun saveImageTranscriptionEnabledModels(models: Set<String>) {
        dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED_MODELS] = models }
    }
    suspend fun saveImageTranscriptionEnabled(enabled: Boolean) {
        dataStore.edit { it[IMAGE_TRANSCRIPTION_ENABLED] = enabled }
    }
    suspend fun saveImageTranscriptionModel(model: String?) {
        dataStore.edit {
            if (model == null) it.remove(IMAGE_TRANSCRIPTION_MODEL)
            else it[IMAGE_TRANSCRIPTION_MODEL] = model
        }
    }
    suspend fun saveImageTranscriptionBatchSize(size: Int) {
        dataStore.edit { it[IMAGE_TRANSCRIPTION_BATCH_SIZE] = size.coerceIn(1, 10) }
    }
    suspend fun saveImageTranscriptionPrompt(prompt: String) {
        dataStore.edit {
            if (prompt.isBlank()) it.remove(IMAGE_TRANSCRIPTION_PROMPT)
            else it[IMAGE_TRANSCRIPTION_PROMPT] = prompt
        }
    }
}
