package com.newoether.agora.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato del fix del SIGABRT (2026-10-26, R5CT422CGKH pid 8443): una CancellationException
 * manual lanzada dentro de acceptProviderPass queda pendiente ante ART en el resume de
 * UndispatchedCoroutine y aborta el proceso entero. Pinea que el rechazo del mailbox viaja
 * como valor Boolean (caller cierra STOPPED por el path ordinario) y que el fence de identidad
 * del outcome permanece intacto — el rechazo no cambia, solo el mecanismo de señalización.
 */
class GenerationPassAcceptanceContractTest {
    @Test
    fun providerPassRejectionTravelsAsValueNotAsManualCancellation() {
        val generate = source("app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt")
        val acceptance = generate.section(
            "suspend fun acceptProviderPass(outcome: ProviderPassOutcome): Boolean {",
            "val apiPath = if (providerConfig.requestResolver != null)",
        )
        // La señal manual que dejaba pending-exception ante ART no puede volver a este path.
        assertFalse(
            "acceptProviderPass no debe lanzar CancellationException manual (SIGABRT 2026-10-26)",
            acceptance.contains("throw CancellationException"),
        )
        // El fence de identidad permanece: un outcome stale sigue siendo ilegal de consumir.
        assertTrue(acceptance.contains("it.identity == outcome.identity && it.result == result"))
        // Ambos motivos de rechazo (stale fence y pass cancelado) producen false, no throw.
        assertEquals(2, acceptance.countToken("return false"))
        assertTrue(acceptance.contains("is ProviderPassOutcome.Cancelled"))
        // El happy path acepta con true — exactamente una aceptación por outcome.
        assertEquals(1, acceptance.countToken("return true"))
    }

    @Test
    fun bothCallersCloseARejectedPassAsStoppedThroughTheOrdinaryPath() {
        val generate = source("app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt")
        // Primer pass: rechazo → STOPPED + salida del lambda del stream scope (el finally
        // NonCancellable hace la finalización durable; sin cancelar el Job).
        val firstPass = generate.section(
            "if (!acceptProviderPass(collectProviderRequest(apiPath)",
            "thoughtTiming.finishCurrent()",
        )
        assertOrdered(
            firstPass,
            "currentStatus = MessageStatus.STOPPED",
            "return@withStreamScope GenerationExecutionResult()",
        )
        // Pass de continuación del tool loop: mismo contrato, termina la ronda sin excepción.
        val toolPass = generate.section(
            "if (!acceptProviderPass(collectProviderRequest(apiToolPath)))",
            "thoughtTiming.finishCurrent()",
        )
        assertOrdered(
            toolPass,
            "currentStatus = MessageStatus.STOPPED",
            "break",
        )
    }

    private fun String.countToken(token: String): Int = split(token).size - 1

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private fun assertOrdered(source: String, vararg values: String) {
        var previous = -1
        values.forEach { value ->
            val current = source.indexOf(value)
            assertTrue("Missing or out-of-order source token: $value", current > previous)
            previous = current
        }
    }

    private fun source(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let {
                return it.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Source file not found: $relativePath")
    }
}
