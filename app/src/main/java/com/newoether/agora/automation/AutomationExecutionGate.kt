package com.newoether.agora.automation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small coroutine read/write gate for Automation generation versus destructive bulk import.
 * Normal Task/Loop executions may run concurrently; an exclusive import first blocks newcomers,
 * waits for existing executions to finish/cancel, and then owns the graph until its transaction
 * completes. Cancellation while acquiring exclusivity always reopens the gate.
 */
class AutomationExecutionGate {
    private val mutex = Mutex()
    private var maintenance = false
    private var activeExecutions = 0
    private var idle = completedSignal()
    private var maintenanceReleased = completedSignal()

    suspend fun <T> withExecution(block: suspend () -> T): T {
        while (true) {
            val waitForMaintenance = mutex.withLock {
                if (maintenance) {
                    maintenanceReleased
                } else {
                    if (activeExecutions == 0) idle = CompletableDeferred()
                    activeExecutions += 1
                    null
                }
            }
            if (waitForMaintenance == null) break
            waitForMaintenance.await()
        }

        return try {
            block()
        } finally {
            mutex.withLock {
                activeExecutions -= 1
                check(activeExecutions >= 0) { "Automation execution gate underflow" }
                if (activeExecutions == 0) idle.complete(Unit)
            }
        }
    }

    suspend fun <T> withExclusiveImport(
        onQuiescing: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        var ownsMaintenance = false
        try {
            while (!ownsMaintenance) {
                val acquisition = mutex.withLock {
                    if (maintenance) {
                        ExclusiveAcquisition(false, maintenanceReleased)
                    } else {
                        maintenance = true
                        maintenanceReleased = CompletableDeferred()
                        ExclusiveAcquisition(
                            ownsMaintenance = true,
                            waitForIdle = idle.takeIf { activeExecutions > 0 },
                        )
                    }
                }
                if (acquisition.ownsMaintenance) {
                    ownsMaintenance = true
                    // New executions are blocked now. Cancel queued/running Workers before
                    // waiting for active engine calls to drain, otherwise a long provider call
                    // could make destructive import wait indefinitely.
                    onQuiescing()
                    // Bounded drain: onQuiescing cancela WorkManager jobs, pero una ejecución
                    // directa del daemon (heartbeat loop) no es alcanzable por esa vía — el
                    // await indefinido dejaba el import colgado en "Importando..." para siempre
                    // (verificado on-device 2026-10-18). Fail-closed con error claro en vez de
                    // hang: el import aborta y el usuario puede apagar el daemon y reintentar.
                    acquisition.waitForIdle?.let { idleSignal ->
                        val drained = kotlinx.coroutines.withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                            idleSignal.await()
                        }
                        check(drained != null) {
                            "Automation did not quiesce within ${DRAIN_TIMEOUT_MS / 1000}s; " +
                                "disable the daemon and retry the import"
                        }
                    }
                } else {
                    acquisition.waitForIdle?.await()
                }
            }
            return block()
        } finally {
            if (ownsMaintenance) {
                mutex.withLock {
                    maintenance = false
                    maintenanceReleased.complete(Unit)
                }
            }
        }
    }

    private data class ExclusiveAcquisition(
        val ownsMaintenance: Boolean,
        val waitForIdle: CompletableDeferred<Unit>?,
    )

    private companion object {
        /**
         * Límite del drain de ejecuciones activas antes del import exclusivo. onQuiescing cancela
         * Workers de WorkManager, pero una ejecución directa del daemon (heartbeat loop) vive en
         * su propio scope y esa vía no la alcanza: sin límite, el import colgaba en
         * "Importando..." indefinidamente (verificado on-device 2026-10-18). Un heartbeat genera
         * en segundos; 60s cubre generaciones largas con margen sin esperar para siempre.
         */
        const val DRAIN_TIMEOUT_MS = 60_000L

        fun completedSignal() = CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}
