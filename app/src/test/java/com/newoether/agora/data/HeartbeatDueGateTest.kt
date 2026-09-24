package com.newoether.agora.data

import com.newoether.agora.data.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatDueGateTest {
    private fun manager(
        heartbeatEnabled: Boolean,
        lastEpochMs: Long,
        activeHoursStart: Int = 0,
        activeHoursEnd: Int = 24,
    ): HeartbeatManager {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.heartbeatEnabled } returns MutableStateFlow(heartbeatEnabled)
        every { settings.heartbeatIntervalMinutes } returns MutableStateFlow(5)
        every { settings.heartbeatActiveHoursStart } returns MutableStateFlow(activeHoursStart)
        every { settings.heartbeatActiveHoursEnd } returns MutableStateFlow(activeHoursEnd)
        every { settings.heartbeatLastHeartbeatEpochMs } returns MutableStateFlow(lastEpochMs)
        return HeartbeatManager(
            settingsRepository = settings,
            memoryManager = mockk(relaxed = true),
            taskManager = mockk(relaxed = true),
            conversationRepository = mockk(relaxed = true),
            chatDao = mockk(relaxed = true),
        )
    }

    /** Epoch ms of a fixed date at [hour]:00 in the test machine's zone — the gate uses systemDefault. */
    private fun epochAtHour(hour: Int): Long =
        java.time.LocalDate.of(2026, 9, 23)
            .atTime(hour, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun heartbeatIsNeverDueWhenDisabledEvenIfIntervalElapsed() {
        val manager = manager(
            heartbeatEnabled = false,
            lastEpochMs = System.currentTimeMillis() - 10 * 60_000L,
        )
        assertFalse(manager.isHeartbeatDue())
    }

    @Test
    fun heartbeatIsDueWhenEnabledAndIntervalElapsed() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = System.currentTimeMillis() - 10 * 60_000L,
        )
        assertTrue(manager.isHeartbeatDue())
    }

    @Test
    fun heartbeatIsNotDueWhenEnabledButIntervalNotElapsed() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = System.currentTimeMillis(),
        )
        assertFalse(manager.isHeartbeatDue())
    }

    // ── Midnight-wrap active-hours window (e.g., 22 → 8) ──────────────────────

    @Test
    fun wrapWindowIncludesLateEveningHours() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 22,
            activeHoursEnd = 8,
        )
        assertTrue(manager.isHeartbeatDue(epochAtHour(23)))
    }

    @Test
    fun wrapWindowIncludesEarlyMorningHours() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 22,
            activeHoursEnd = 8,
        )
        assertTrue(manager.isHeartbeatDue(epochAtHour(6)))
    }

    @Test
    fun wrapWindowExcludesMiddayHours() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 22,
            activeHoursEnd = 8,
        )
        assertFalse(manager.isHeartbeatDue(epochAtHour(12)))
    }

    @Test
    fun wrapWindowStartBoundaryIsInclusive() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 22,
            activeHoursEnd = 8,
        )
        assertTrue(manager.isHeartbeatDue(epochAtHour(22)))
    }

    @Test
    fun wrapWindowEndBoundaryIsExclusive() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 22,
            activeHoursEnd = 8,
        )
        assertFalse(manager.isHeartbeatDue(epochAtHour(8)))
    }

    @Test
    fun sameDayWindowExcludesHoursBeforeStart() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 8,
            activeHoursEnd = 22,
        )
        assertFalse(manager.isHeartbeatDue(epochAtHour(7)))
        assertTrue(manager.isHeartbeatDue(epochAtHour(8)))
        assertFalse(manager.isHeartbeatDue(epochAtHour(22)))
    }

    @Test
    fun fullDayWindowNeverBlocks() {
        val manager = manager(
            heartbeatEnabled = true,
            lastEpochMs = 0L,
            activeHoursStart = 0,
            activeHoursEnd = 24,
        )
        assertTrue(manager.isHeartbeatDue(epochAtHour(0)))
        assertTrue(manager.isHeartbeatDue(epochAtHour(23)))
    }
}