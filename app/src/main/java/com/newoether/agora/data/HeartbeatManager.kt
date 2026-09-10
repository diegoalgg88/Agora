package com.newoether.agora.data

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.HeartbeatLogEntity
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Manages heartbeat configuration, scheduling, and logging.
 *
 * Configuration is stored in DataStore via SettingsManager.
 * Log entries (max 5) are stored in Room via ChatHeartbeatSmsDao.
 */
class HeartbeatManager(
    private val settingsRepository: SettingsRepository,
    private val memoryManager: MemoryManager,
    private val taskManager: com.newoether.agora.automation.TaskManager,
    private val conversationRepository: ConversationRepository,
    private val chatDao: ChatDao,
) {

    // Heartbeat configuration (StateFlow from SettingsRepository)
    val enabled: kotlinx.coroutines.flow.StateFlow<Boolean> = settingsRepository.heartbeatEnabled
    val intervalMinutes: kotlinx.coroutines.flow.StateFlow<Int> = settingsRepository.heartbeatIntervalMinutes
    val activeHoursStart: kotlinx.coroutines.flow.StateFlow<Int> = settingsRepository.heartbeatActiveHoursStart
    val activeHoursEnd: kotlinx.coroutines.flow.StateFlow<Int> = settingsRepository.heartbeatActiveHoursEnd
    val lastHeartbeatEpochMs: kotlinx.coroutines.flow.StateFlow<Long> = settingsRepository.heartbeatLastHeartbeatEpochMs
    val heartbeatInstanceId: kotlinx.coroutines.flow.StateFlow<String?> = settingsRepository.heartbeatInstanceId
    val heartbeatPrompt: kotlinx.coroutines.flow.StateFlow<String> = settingsRepository.heartbeatPrompt
    val heartbeatModel: kotlinx.coroutines.flow.StateFlow<String?> = settingsRepository.heartbeatModel

    suspend fun setEnabled(enabled: Boolean) = settingsRepository.saveHeartbeatEnabled(enabled)
    suspend fun setIntervalMinutes(minutes: Int) = settingsRepository.saveHeartbeatIntervalMinutes(minutes)
    suspend fun setActiveHoursStart(hour: Int) = settingsRepository.saveHeartbeatActiveHoursStart(hour)
    suspend fun setActiveHoursEnd(hour: Int) = settingsRepository.saveHeartbeatActiveHoursEnd(hour)
    suspend fun setLastHeartbeatEpochMs(epochMs: Long) = settingsRepository.saveHeartbeatLastHeartbeatEpochMs(epochMs)
    suspend fun setHeartbeatInstanceId(instanceId: String?) = settingsRepository.saveHeartbeatInstanceId(instanceId)
    suspend fun setHeartbeatPrompt(prompt: String) = settingsRepository.saveHeartbeatPrompt(prompt)
    suspend fun setHeartbeatModel(model: String?) = settingsRepository.saveHeartbeatModel(model)

    /**
     * Checks if a heartbeat is due based on the enabled flag, interval, and active hours.
     */
    fun isHeartbeatDue(): Boolean {
        if (!enabled.value) return false

        val now = System.currentTimeMillis()
        val last = lastHeartbeatEpochMs.value
        val intervalMs = intervalMinutes.value.toLong() * 60_000L
        if (now - last < intervalMs) return false

        // Check active hours
        val currentHour = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour
        val start = activeHoursStart.value
        val end = activeHoursEnd.value
        if (start <= end) {
            return currentHour >= start && currentHour < end
        } else {
            // Wraps midnight (e.g., 22 to 8)
            return currentHour >= start || currentHour < end
        }
    }

    /** The 5 most recent heartbeat runs, newest first. */
    val recentLogs: Flow<List<HeartbeatLogEntity>> = chatDao.getRecentHeartbeatLogsFlow()

    /**
     * Records the outcome of one heartbeat run in Room (kept to the 5 newest rows).
     * Called by the scheduler after every run, scheduled or manual.
     */
    suspend fun recordHeartbeat(success: Boolean, error: String? = null) = withContext(Dispatchers.IO) {
        chatDao.insertHeartbeatLog(
            HeartbeatLogEntity(
                timestampEpochMs = System.currentTimeMillis(),
                success = success,
                error = error,
            ),
        )
    }

    companion object {
        const val DEFAULT_HEARTBEAT_PROMPT =
            "[HEARTBEAT] This is an automatic self-check. Review your memories and pending tasks. " +
                "If everything looks good and nothing needs attention, respond with exactly: HEARTBEAT_OK\n" +
                "If something needs attention (stale memories, due tasks, user follow-ups), address it.\n" +
                "You cannot enable, disable, or reschedule heartbeat — the schedule is a user setting."
    }
}