package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable owner of the default generation-parameter preference keys. Extracted from
 * [SettingsManager] so that class stays under the 999-line source policy; every member is a
 * pure DataStore read or write with no cross-key logic — the caller (SettingsManager) keeps
 * the same public surface.
 */
internal class GenerationDefaultsStore(private val dataStore: DataStore<Preferences>) {

    val defaultTemperature: Flow<Float?> =
        dataStore.data.map { it[DEFAULT_TEMPERATURE]?.toFloatOrNull() }
    val defaultMaxTokens: Flow<Int?> = dataStore.data.map { it[DEFAULT_MAX_TOKENS] }
    val defaultTopP: Flow<Float?> = dataStore.data.map { it[DEFAULT_TOP_P]?.toFloatOrNull() }
    val defaultFrequencyPenalty: Flow<Float?> =
        dataStore.data.map { it[DEFAULT_FREQUENCY_PENALTY]?.toFloatOrNull() }
    val defaultPresencePenalty: Flow<Float?> =
        dataStore.data.map { it[DEFAULT_PRESENCE_PENALTY]?.toFloatOrNull() }
    val defaultRepetitionPenalty: Flow<Float?> =
        dataStore.data.map { it[DEFAULT_REPETITION_PENALTY]?.toFloatOrNull() }

    suspend fun saveDefaultTemperature(value: Float?) = saveFloat(DEFAULT_TEMPERATURE, value)
    suspend fun saveDefaultMaxTokens(value: Int?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(DEFAULT_MAX_TOKENS) else prefs[DEFAULT_MAX_TOKENS] = value
        }
    }
    suspend fun saveDefaultTopP(value: Float?) = saveFloat(DEFAULT_TOP_P, value)
    suspend fun saveDefaultFrequencyPenalty(value: Float?) =
        saveFloat(DEFAULT_FREQUENCY_PENALTY, value)
    suspend fun saveDefaultPresencePenalty(value: Float?) =
        saveFloat(DEFAULT_PRESENCE_PENALTY, value)
    suspend fun saveDefaultRepetitionPenalty(value: Float?) =
        saveFloat(DEFAULT_REPETITION_PENALTY, value)

    /** Removes every key this store owns. Used by the portable-settings REPLACE path. */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(DEFAULT_TEMPERATURE)
            prefs.remove(DEFAULT_MAX_TOKENS)
            prefs.remove(DEFAULT_TOP_P)
            prefs.remove(DEFAULT_FREQUENCY_PENALTY)
            prefs.remove(DEFAULT_PRESENCE_PENALTY)
            prefs.remove(DEFAULT_REPETITION_PENALTY)
        }
    }

    private suspend fun saveFloat(key: Preferences.Key<String>, value: Float?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value.toString()
        }
    }
}
