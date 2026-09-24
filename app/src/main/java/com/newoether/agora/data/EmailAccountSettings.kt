package com.newoether.agora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.newoether.agora.util.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Email account settings (connected accounts, encrypted passwords, poll interval)
 * flows and savers. Separated from SettingsManager to keep file sizes under the
 * 999-line limit. Shares the same settings DataStore and preference keys.
 *
 * Account records never contain the password — it lives in a separate encrypted
 * map keyed by account id, so account lists can be exported, logged, or rendered
 * without credential exposure. SecretCrypto falls back to plaintext when Keystore
 * encryption fails (documented SettingsManager behavior, not a new invariant).
 */
class EmailAccountSettings(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {

    /** Connected accounts, in connection order. */
    val accounts: Flow<List<EmailAccount>> = dataStore.data.map { prefs ->
        decodeAccounts(SecretCrypto.decrypt(prefs[EMAIL_ACCOUNTS_JSON] ?: "[]"))
    }

    /** Poll interval in minutes; 0 disables background email polling. */
    val pollIntervalMinutes: Flow<Int> = dataStore.data.map {
        it[EMAIL_POLL_INTERVAL_MINUTES] ?: DEFAULT_EMAIL_POLL_INTERVAL_MINUTES
    }

    /** Adds (or replaces, keyed by id) an account plus its password in one edit. */
    suspend fun addAccount(account: EmailAccount, password: String) {
        dataStore.edit { prefs ->
            val current = decodeAccounts(SecretCrypto.decrypt(prefs[EMAIL_ACCOUNTS_JSON] ?: "[]"))
            val updated = current.filter { it.id != account.id } + account
            prefs[EMAIL_ACCOUNTS_JSON] = SecretCrypto.encrypt(json.encodeToString(updated))

            val passwords = decodePasswords(prefs)
            if (password.isBlank()) passwords.remove(account.id) else passwords[account.id] = password
            savePasswords(prefs, passwords)
        }
    }

    /** Removes an account and its password together — no orphaned credentials. */
    suspend fun removeAccount(accountId: String) {
        dataStore.edit { prefs ->
            val current = decodeAccounts(SecretCrypto.decrypt(prefs[EMAIL_ACCOUNTS_JSON] ?: "[]"))
            val updated = current.filter { it.id != accountId }
            if (updated.isEmpty()) prefs.remove(EMAIL_ACCOUNTS_JSON)
            else prefs[EMAIL_ACCOUNTS_JSON] = SecretCrypto.encrypt(json.encodeToString(updated))

            val passwords = decodePasswords(prefs)
            passwords.remove(accountId)
            savePasswords(prefs, passwords)
        }
    }

    suspend fun updatePassword(accountId: String, password: String) {
        dataStore.edit { prefs ->
            val passwords = decodePasswords(prefs)
            if (password.isBlank()) passwords.remove(accountId) else passwords[accountId] = password
            savePasswords(prefs, passwords)
        }
    }

    /** One-shot password lookup for senders and pollers (no flow subscription). */
    suspend fun passwordFor(accountId: String): String? {
        val prefs = dataStore.data.first()
        return decodePasswords(prefs)[accountId]
    }

    /** One-shot account list for the poller's account provider. */
    suspend fun accountsOnce(): List<EmailAccount> = accounts.first()

    suspend fun savePollIntervalMinutes(minutes: Int) {
        dataStore.edit { it[EMAIL_POLL_INTERVAL_MINUTES] = minutes.coerceIn(0, 1440) }
    }

    /** Import hook for the portable archive: replaces the whole account list. */
    suspend fun importAccounts(accounts: List<EmailAccount>, passwords: Map<String, String>) {
        dataStore.edit { prefs ->
            if (accounts.isEmpty()) {
                prefs.remove(EMAIL_ACCOUNTS_JSON)
            } else {
                prefs[EMAIL_ACCOUNTS_JSON] = SecretCrypto.encrypt(json.encodeToString(accounts))
            }
            val merged = decodePasswords(prefs) + passwords
            savePasswords(prefs, merged)
        }
    }

    /** Export hook for the portable archive (secrets gate applied by the caller). */
    suspend fun exportPasswords(): Map<String, String> = decodePasswords(dataStore.data.first())

    private fun decodeAccounts(raw: String): List<EmailAccount> = try {
        json.decodeFromString<List<EmailAccount>>(raw)
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodePasswords(prefs: Preferences): MutableMap<String, String> = try {
        json.decodeFromString<MutableMap<String, String>>(
            SecretCrypto.decrypt(prefs[EMAIL_PASSWORDS_JSON] ?: "{}"),
        )
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun savePasswords(prefs: androidx.datastore.preferences.core.MutablePreferences, passwords: Map<String, String>) {
        if (passwords.isEmpty()) prefs.remove(EMAIL_PASSWORDS_JSON)
        else prefs[EMAIL_PASSWORDS_JSON] = SecretCrypto.encrypt(json.encodeToString(passwords))
    }
}
