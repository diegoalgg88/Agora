package com.newoether.agora.automation

import com.newoether.agora.data.HeartbeatManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * extractActionableHeartbeatText contract tests (plan PLAN-20261002-TASK-CONFIRM,
 * decision 8). The heartbeat prompt asks the model for HEARTBEAT_OK when nothing needs
 * attention; the common clean heartbeat is therefore silent and must NOT stage a rich
 * confirmation — staging it would invert the feature's purpose.
 */
class HeartbeatActionableTextTest {

    private val scheduler = HeartbeatScheduler(
        appContext = mockk {
            every { applicationContext } returns this
        },
        heartbeatManager = mockk(relaxed = true),
        settingsRepository = mockk(relaxed = true),
        smsStore = mockk(relaxed = true),
        smsPoller = mockk(relaxed = true),
        notificationStore = mockk(relaxed = true),
        heartbeatNotifier = mockk(relaxed = true),
        taskExecutionEngine = mockk(relaxed = true),
        appForegroundTracker = mockk(relaxed = true),
        loopManager = mockk(relaxed = true),
        emailStore = mockk(relaxed = true),
        emailPoller = mockk(relaxed = true),
        taskConfirmationStore = mockk(relaxed = true),
        taskPromptNotifier = mockk(relaxed = true),
    )

    @Test
    fun emptyResultIsSilent() {
        assertNull(scheduler.extractActionableHeartbeatText(""))
        assertNull(scheduler.extractActionableHeartbeatText("   \n\t "))
    }

    @Test
    fun pureSentinelIsSilent() {
        assertNull(scheduler.extractActionableHeartbeatText("HEARTBEAT_OK"))
        assertNull(scheduler.extractActionableHeartbeatText("heartbeat_ok"))
        assertNull(scheduler.extractActionableHeartbeatText("  Heartbeat_Ok  "))
    }

    @Test
    fun sentinelPrefixIsStrippedKeepingSubstantiveRemainder() {
        assertEquals(
            "el backup lleva 3 días fallando",
            scheduler.extractActionableHeartbeatText("HEARTBEAT_OK — el backup lleva 3 días fallando"),
        )
        assertEquals(
            "una tarea vence mañana",
            scheduler.extractActionableHeartbeatText("HEARTBEAT_OK: una tarea vence mañana"),
        )
        assertEquals(
            "línea dos",
            scheduler.extractActionableHeartbeatText("HEARTBEAT_OK\nlínea dos"),
        )
    }

    @Test
    fun sentinelPrefixWithOnlySeparatorsRemainderIsSilent() {
        assertNull(scheduler.extractActionableHeartbeatText("HEARTBEAT_OK —"))
        assertNull(scheduler.extractActionableHeartbeatText("HEARTBEAT_OK: \n"))
    }

    @Test
    fun plainActionableTextPassesThroughTrimmed() {
        assertEquals("algo importante", scheduler.extractActionableHeartbeatText(" algo importante "))
    }

    @Test
    fun sentinelMustBeAPrefixNotEmbedded() {
        // The sentinel embedded mid-text is content, not a prefix marker.
        assertEquals(
            "dijo HEARTBEAT_OK y siguió",
            scheduler.extractActionableHeartbeatText("dijo HEARTBEAT_OK y siguió"),
        )
    }

    @Test
    fun decoratedSentinelIsSilent() {
        assertNull(scheduler.extractActionableHeartbeatText("**HEARTBEAT_OK**"))
        assertNull(scheduler.extractActionableHeartbeatText("`HEARTBEAT_OK`"))
        assertNull(scheduler.extractActionableHeartbeatText("HEARTBEAT_OK."))
        assertNull(scheduler.extractActionableHeartbeatText("\"HEARTBEAT_OK\""))
    }

    @Test
    fun decoratedSentinelPrefixKeepsSubstantiveRemainder() {
        assertEquals(
            "una tarea vence mañana",
            scheduler.extractActionableHeartbeatText("**HEARTBEAT_OK** — una tarea vence mañana"),
        )
    }

    @Test
    fun sentinelLookalikeWordIsContent() {
        assertEquals(
            "HEARTBEAT_OKAY pero falló algo",
            scheduler.extractActionableHeartbeatText("HEARTBEAT_OKAY pero falló algo"),
        )
    }

    @Test
    fun constantMatchesPromptSentinel() {
        // The prompt and the filter must never diverge.
        assertEquals("HEARTBEAT_OK", HeartbeatManager.HEARTBEAT_OK_SENTINEL)
    }

    // ── plainTextForConfirmation (2026-10-03 device-run fix: markdown leaked raw) ──

    @Test
    fun markdownBoldAndHeadingsAreStripped() {
        val raw = "**Resumen de nuevos elementos**\n\n## SMS\n- +52 123 nueva tarea pendiente"
        org.junit.Assert.assertEquals(
            "Resumen de nuevos elementos\nSMS\n+52 123 nueva tarea pendiente",
            scheduler.plainTextForConfirmation(raw),
        )
    }

    @Test
    fun inlineCodeAndEmphasisUnderscoresAreStripped() {
        val raw = "El `pin_memory_file` falló con __error 500__"
        org.junit.Assert.assertEquals(
            "El pin_memory_file falló con error 500",
            scheduler.plainTextForConfirmation(raw),
        )
    }

    @Test
    fun bulletMarkersKeepItemContent() {
        val raw = "- primera\n* segunda\n+ tercera\n• cuarta"
        org.junit.Assert.assertEquals(
            "primera\nsegunda\ntercera\ncuarta",
            scheduler.plainTextForConfirmation(raw),
        )
    }

    @Test
    fun plainTextPassesThroughAndBlankLinesCollapse() {
        org.junit.Assert.assertEquals(
            "línea uno\nlínea dos",
            scheduler.plainTextForConfirmation("línea uno\n\n\n   \nlínea dos"),
        )
    }

    @Test
    fun emptyAndMarkdownOnlyInputYieldsEmptyOutput() {
        org.junit.Assert.assertEquals("", scheduler.plainTextForConfirmation(""))
        org.junit.Assert.assertEquals("", scheduler.plainTextForConfirmation("**###**"))
    }

    @Test
    fun orderedListNumbersAreContentAndSurvive() {
        // "1. item" starts with a digit, not a marker — content passes through untouched.
        org.junit.Assert.assertEquals(
            "1. instalar el parche",
            scheduler.plainTextForConfirmation("1. instalar el parche"),
        )
    }
}
