package com.newoether.agora.automation

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-T1: scheduled executions prepend a factual execution-time header to the persisted
 * prompt, so an unattended model cannot misinfer "today" (the weather task answered with a
 * 2025 date on 2026-10-08). [com.newoether.agora.data.local.TaskEntity.prompt] itself is
 * never modified. The header is locale-free ISO so it is deterministic in tests and
 * unambiguous for the model.
 */
class TaskExecutionPromptHeaderTest {

    private val utc = ZoneId.of("UTC")
    // 2026-10-08 17:46 UTC
    private val at = java.time.ZonedDateTime
        .of(2026, 10, 8, 17, 46, 0, 0, utc)
        .toInstant()
        .toEpochMilli()

    @Test
    fun `header precedes the user prompt verbatim`() {
        val composed = TaskManager.composeExecutionPrompt("Genera el pronóstico", at, utc)
        assertEquals(
            "Fecha y hora de ejecución: 2026-10-08 17:46 (UTC).\n\nGenera el pronóstico",
            composed,
        )
    }

    @Test
    fun `header respects the execution timezone`() {
        val monterrey = ZoneId.of("America/Monterrey")
        val composed = TaskManager.composeExecutionPrompt("p", at, monterrey)
        assertTrue(
            composed.startsWith("Fecha y hora de ejecución: 2026-10-08 11:46 (America/Monterrey)."),
        )
    }

    @Test
    fun `prompt with its own date lines is never rewritten only prefixed`() {
        val prompt = "Paso 1 — Obtener datos\nFecha límite: 2025-01-01"
        val composed = TaskManager.composeExecutionPrompt(prompt, at, utc)
        assertTrue(composed.endsWith(prompt))
        assertFalse(composed.contains("2025-01-01\n"))
    }
}
