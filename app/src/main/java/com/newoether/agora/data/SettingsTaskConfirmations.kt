package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.newoether.agora.data.local.TaskConfirmationCardStyle
import com.newoether.agora.data.local.TaskConfirmationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Task-confirmation-specific settings flows and functions (plan PLAN-20261002-TASK-CONFIRM).
 * Separated from SettingsManager to keep file sizes under the 999-line limit — same
 * pattern as [SettingsNotifications]. Wire values are owned by the typed enums so an
 * unknown/legacy value fails closed to the default instead of throwing.
 */
class SettingsTaskConfirmations(
    private val dataStore: DataStore<Preferences>,
) {
    // DataStore keys (schema mirrors in SettingsPreferenceSchema for archive tooling)
    private val ENABLED = booleanPreferencesKey("task_confirmation_enabled")
    private val MODE = stringPreferencesKey("task_confirmation_mode")
    private val CARD_STYLE = stringPreferencesKey("task_confirmation_card_style")

    // ── Flows ────────────────────────────────────────────────────────

    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }

    val mode: Flow<TaskConfirmationMode> = dataStore.data.map {
        TaskConfirmationMode.fromWire(it[MODE] ?: TaskConfirmationMode.DEFAULT.name)
    }

    val cardStyle: Flow<TaskConfirmationCardStyle> = dataStore.data.map {
        TaskConfirmationCardStyle.fromWire(it[CARD_STYLE] ?: TaskConfirmationCardStyle.DEFAULT.name)
    }

    // ── Saves ────────────────────────────────────────────────────────

    suspend fun saveEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun saveMode(mode: TaskConfirmationMode) {
        dataStore.edit { it[MODE] = mode.name }
    }

    suspend fun saveCardStyle(style: TaskConfirmationCardStyle) {
        dataStore.edit { it[CARD_STYLE] = style.name }
    }
}
