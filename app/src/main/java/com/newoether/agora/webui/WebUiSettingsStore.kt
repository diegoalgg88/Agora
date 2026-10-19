package com.newoether.agora.webui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Preferencias del WebUI. La contraseña se guarda SOLO como su hash PBKDF2. Estas keys son
 * device-local: no forman parte del archive portable de settings, así un backup jamás lleva la
 * contraseña del WebUI a otro dispositivo.
 */
internal class WebUiSettingsStore(private val dataStore: DataStore<Preferences>) {
    val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }
    val port: Flow<Int> = dataStore.data.map { it[PORT] ?: DEFAULT_PORT }
    val passwordHash: Flow<String?> = dataStore.data.map { it[PASSWORD_HASH] }
    val https: Flow<Boolean> = dataStore.data.map { it[HTTPS] ?: true }

    suspend fun saveEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun savePort(port: Int) {
        require(port in PORT_RANGE) { "Port $port is outside $PORT_RANGE" }
        dataStore.edit { it[PORT] = port }
    }

    suspend fun saveHttps(enabled: Boolean) {
        dataStore.edit { it[HTTPS] = enabled }
    }

    suspend fun savePasswordHash(hash: String) {
        dataStore.edit { it[PASSWORD_HASH] = hash }
    }

    companion object {
        const val DEFAULT_PORT = 8686
        /** Solo puertos no privilegiados; una app Android no puede bindear bajo 1024. */
        val PORT_RANGE = 1024..65535
        private val ENABLED = booleanPreferencesKey("webui_enabled")
        private val PORT = intPreferencesKey("webui_port")
        private val PASSWORD_HASH = stringPreferencesKey("webui_password_hash")
        private val HTTPS = booleanPreferencesKey("webui_https")
    }
}
