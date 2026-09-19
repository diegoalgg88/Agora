package com.newoether.agora.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Toggles for the assistant features: the device-action tools (`AssistantDeviceToolProvider`)
 * and the system-assistant overlay (`assistant/` package). Kept out of [SettingsManager] to
 * respect the 999-line source-size policy. All tools are opt-in (default off); see
 * `development/assistant-device-tools.md` and `development/system-assistant.md`.
 */
class AssistantToolSettings(private val context: Context) {

    val setAlarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SET_ALARM] ?: false }
    val openFileEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_OPEN_FILE] ?: false }
    val createCalendarEventEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CREATE_CALENDAR_EVENT] ?: false }
    val listCalendarEventsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_LIST_CALENDAR_EVENTS] ?: false }
    val updateCalendarEventEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_UPDATE_CALENDAR_EVENT] ?: false }
    val deleteCalendarEventEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_DELETE_CALENDAR_EVENT] ?: false }
    val getLocationEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_GET_LOCATION] ?: false }
    val getLocalTimeEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_GET_LOCAL_TIME] ?: false }
    val openUrlEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_OPEN_URL] ?: false }
    val sendNotificationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_SEND_NOTIFICATION] ?: false }

    /** When on, the system-assistant overlay reuses the newest "assistant"-origin conversation
     * instead of creating a fresh one per activation (owner decision 2026-09-15). */
    val reuseConversationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_REUSE_CONVERSATION] ?: false }

    /** System-assistant overlay: attach the Assist API screenshot to the first message. */
    val attachScreenshotEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_ATTACH_SCREENSHOT] ?: true }

    /** System-assistant overlay: include the AssistStructure screen text in the prompt. */
    val includeScreenTextEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_INCLUDE_SCREEN_TEXT] ?: true }

    /** System-assistant overlay: show the mic button for voice dictation. */
    val voiceInputEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_VOICE_INPUT] ?: true }

    /** Live voice-to-voice calls (Phase 4, preview). Default off: audio streams continuously
     *  and native-audio models burn tokens for the whole call. */
    val voiceToVoiceEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_VOICE_TO_VOICE] ?: false }

    /** Live calls reuse the newest "assistant-voice" conversation (default on — a voice call is
     *  naturally continuous, unlike the one-shot text overlay). */
    val voiceToVoiceReuseConversationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_VOICE_TO_VOICE_REUSE] ?: true }

    /** Free-text Live model id; preview models rotate often, so it stays editable.
     *  Re-verify this default against the docs on major updates (plan §10.9.2). */
    val voiceToVoiceModelId: Flow<String> =
        context.dataStore.data.map { it[KEY_VOICE_TO_VOICE_MODEL] ?: DEFAULT_LIVE_MODEL }

    /** Prebuilt TTS voice name (e.g. Kore/Puck/Fenrir), free text. */
    val voiceToVoiceVoiceName: Flow<String> =
        context.dataStore.data.map { it[KEY_VOICE_TO_VOICE_VOICE] ?: DEFAULT_LIVE_VOICE }

    /** VAD sensitivity preset name (see `assistant.live.VoiceSensitivity`); stored as a plain
     *  string here to keep this data-layer class independent of the `assistant.live` feature
     *  package — the enum interprets/validates the value on read (owner report, 2026-09-17:
     *  the original fixed HIGH/500ms tuning cut users off mid-sentence, so it must be a user
     *  preference, not a hardcoded constant). */
    val voiceToVoiceSensitivity: Flow<String> =
        context.dataStore.data.map { it[KEY_VOICE_TO_VOICE_SENSITIVITY] ?: DEFAULT_LIVE_SENSITIVITY }

    suspend fun saveSetAlarmEnabled(enabled: Boolean) = edit(KEY_SET_ALARM, enabled)
    suspend fun saveOpenFileEnabled(enabled: Boolean) = edit(KEY_OPEN_FILE, enabled)
    suspend fun saveCreateCalendarEventEnabled(enabled: Boolean) =
        edit(KEY_CREATE_CALENDAR_EVENT, enabled)
    suspend fun saveListCalendarEventsEnabled(enabled: Boolean) =
        edit(KEY_LIST_CALENDAR_EVENTS, enabled)
    suspend fun saveUpdateCalendarEventEnabled(enabled: Boolean) =
        edit(KEY_UPDATE_CALENDAR_EVENT, enabled)
    suspend fun saveDeleteCalendarEventEnabled(enabled: Boolean) =
        edit(KEY_DELETE_CALENDAR_EVENT, enabled)
    suspend fun saveGetLocationEnabled(enabled: Boolean) = edit(KEY_GET_LOCATION, enabled)
    suspend fun saveGetLocalTimeEnabled(enabled: Boolean) = edit(KEY_GET_LOCAL_TIME, enabled)
    suspend fun saveOpenUrlEnabled(enabled: Boolean) = edit(KEY_OPEN_URL, enabled)
    suspend fun saveSendNotificationEnabled(enabled: Boolean) = edit(KEY_SEND_NOTIFICATION, enabled)
    suspend fun saveReuseConversationEnabled(enabled: Boolean) = edit(KEY_REUSE_CONVERSATION, enabled)
    suspend fun saveAttachScreenshotEnabled(enabled: Boolean) = edit(KEY_ATTACH_SCREENSHOT, enabled)
    suspend fun saveIncludeScreenTextEnabled(enabled: Boolean) = edit(KEY_INCLUDE_SCREEN_TEXT, enabled)
    suspend fun saveVoiceInputEnabled(enabled: Boolean) = edit(KEY_VOICE_INPUT, enabled)
    suspend fun saveVoiceToVoiceEnabled(enabled: Boolean) = edit(KEY_VOICE_TO_VOICE, enabled)
    suspend fun saveVoiceToVoiceReuseConversationEnabled(enabled: Boolean) =
        edit(KEY_VOICE_TO_VOICE_REUSE, enabled)

    suspend fun saveVoiceToVoiceModelId(modelId: String) =
        context.dataStore.edit { it[KEY_VOICE_TO_VOICE_MODEL] = modelId.trim() }

    suspend fun saveVoiceToVoiceVoiceName(voiceName: String) =
        context.dataStore.edit { it[KEY_VOICE_TO_VOICE_VOICE] = voiceName.trim() }

    suspend fun saveVoiceToVoiceSensitivity(value: String) =
        context.dataStore.edit { it[KEY_VOICE_TO_VOICE_SENSITIVITY] = value }

    private suspend fun edit(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, enabled: Boolean) {
        context.dataStore.edit { it[key] = enabled }
    }

    /** Clears every assistant-tool toggle during a full settings reset. */
    internal fun removeAll(prefs: MutablePreferences) {
        prefs.remove(KEY_SET_ALARM)
        prefs.remove(KEY_OPEN_FILE)
        prefs.remove(KEY_CREATE_CALENDAR_EVENT)
        prefs.remove(KEY_LIST_CALENDAR_EVENTS)
        prefs.remove(KEY_UPDATE_CALENDAR_EVENT)
        prefs.remove(KEY_DELETE_CALENDAR_EVENT)
        prefs.remove(KEY_GET_LOCATION)
        prefs.remove(KEY_GET_LOCAL_TIME)
        prefs.remove(KEY_OPEN_URL)
        prefs.remove(KEY_SEND_NOTIFICATION)
        prefs.remove(KEY_REUSE_CONVERSATION)
        prefs.remove(KEY_ATTACH_SCREENSHOT)
        prefs.remove(KEY_INCLUDE_SCREEN_TEXT)
        prefs.remove(KEY_VOICE_INPUT)
        prefs.remove(KEY_VOICE_TO_VOICE)
        prefs.remove(KEY_VOICE_TO_VOICE_REUSE)
        prefs.remove(KEY_VOICE_TO_VOICE_MODEL)
        prefs.remove(KEY_VOICE_TO_VOICE_VOICE)
        prefs.remove(KEY_VOICE_TO_VOICE_SENSITIVITY)
    }

    companion object {
        /** Shared defaults so DataStore, SettingsRepository and the Live client agree. */
        const val DEFAULT_LIVE_MODEL_ID = "gemini-3.1-flash-live-preview"
        const val DEFAULT_LIVE_VOICE_NAME = "Kore"
        /** Mirrors `assistant.live.VoiceSensitivity.DEFAULT.name` ("BALANCED"); kept as a plain
         *  string literal to avoid a data→assistant.live dependency for one constant. */
        const val DEFAULT_LIVE_SENSITIVITY = "BALANCED"
        private const val DEFAULT_LIVE_MODEL = DEFAULT_LIVE_MODEL_ID
        private const val DEFAULT_LIVE_VOICE = DEFAULT_LIVE_VOICE_NAME
        val KEY_SET_ALARM = booleanPreferencesKey("assistant_set_alarm_enabled")
        val KEY_OPEN_FILE = booleanPreferencesKey("assistant_open_file_enabled")
        val KEY_CREATE_CALENDAR_EVENT = booleanPreferencesKey("assistant_create_calendar_event_enabled")
        val KEY_LIST_CALENDAR_EVENTS = booleanPreferencesKey("assistant_list_calendar_events_enabled")
        val KEY_UPDATE_CALENDAR_EVENT = booleanPreferencesKey("assistant_update_calendar_event_enabled")
        val KEY_DELETE_CALENDAR_EVENT = booleanPreferencesKey("assistant_delete_calendar_event_enabled")
        val KEY_GET_LOCATION = booleanPreferencesKey("assistant_get_location_enabled")
        val KEY_GET_LOCAL_TIME = booleanPreferencesKey("assistant_get_local_time_enabled")
        val KEY_OPEN_URL = booleanPreferencesKey("assistant_open_url_enabled")
        val KEY_SEND_NOTIFICATION = booleanPreferencesKey("assistant_send_notification_enabled")
        val KEY_REUSE_CONVERSATION = booleanPreferencesKey("system_assistant_reuse_conversation")
        val KEY_ATTACH_SCREENSHOT = booleanPreferencesKey("system_assistant_attach_screenshot")
        val KEY_INCLUDE_SCREEN_TEXT = booleanPreferencesKey("system_assistant_include_screen_text")
        val KEY_VOICE_INPUT = booleanPreferencesKey("system_assistant_voice_input")
        val KEY_VOICE_TO_VOICE = booleanPreferencesKey("live_voice_enabled")
        val KEY_VOICE_TO_VOICE_REUSE = booleanPreferencesKey("live_voice_reuse_conversation")
        val KEY_VOICE_TO_VOICE_MODEL = androidx.datastore.preferences.core.stringPreferencesKey("live_voice_model")
        val KEY_VOICE_TO_VOICE_VOICE = androidx.datastore.preferences.core.stringPreferencesKey("live_voice_voice_name")
        val KEY_VOICE_TO_VOICE_SENSITIVITY = androidx.datastore.preferences.core.stringPreferencesKey("live_voice_sensitivity")
    }
}
