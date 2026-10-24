package com.newoether.agora.viewmodel

import com.newoether.agora.model.ConversationCommand
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.RunEndReason
import com.newoether.agora.model.RunStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * awaitGenerationSettled espera al owner EXACTO a través de ambas barriers (durable + Job
 * instalado); ni la completion del Job ni la revocación de UI token del Stop la settlea antes,
 * y un sucesor con el slot tomado no extiende la espera.
 */
class GenerationSettlementWaitTest {
    @Test
    fun durableSuccessWaitsForInstalledJob_andDoesNotFollowSuccessor() = runBlocking<Unit> {
        withTimeout(5_000) {
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            try {
                val token = checkNotNull(state.tryAcquireForReplacement())
                val owner = Job()
                assertTrue(state.attachGenerationJob(token, owner))
                state.bindRun(token, "original-run")
                val settled = async(start = CoroutineStart.UNDISPATCHED) {
                    state.awaitGenerationSettled(token)
                }
                val effect = checkNotNull(state.commands.requestRunFinalization(
                    RunEffectIdentity("child", token, "original-run", 0, "terminal"),
                    RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                ))
                state.finishRunFinalization(effect.identity, true)
                // El asiento durable solo no settlea: falta el Job instalado.
                assertFalse(settled.isCompleted)
                owner.complete()
                owner.join()
                settled.await()
                // Un sucesor ya reclamó el slot: la espera del owner viejo no lo sigue.
                val successor = checkNotNull(state.tryAcquireForReplacement())
                state.awaitGenerationSettled(token)
                assertTrue(state.generating.value)
                assertTrue(successor > token)
                state.endGeneration(successor)
            } finally {
                state.dispose()
            }
        }
    }

    @Test
    fun jobCompletionAndFailedPersistenceDoNotSettleTheOwner() = runBlocking<Unit> {
        withTimeout(5_000) {
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            try {
                val token = checkNotNull(state.tryAcquireForReplacement())
                val owner = Job()
                assertTrue(state.attachGenerationJob(token, owner))
                state.bindRun(token, "original-run")
                val settled = async(start = CoroutineStart.UNDISPATCHED) {
                    state.awaitGenerationSettled(token)
                }
                owner.complete()
                owner.join()
                state.endGeneration(token)
                // Ni el Job completado ni el slot liberado settlean: falta el asiento durable.
                assertFalse(settled.isCompleted)
                val effect = checkNotNull(state.commands.requestRunFinalization(
                    RunEffectIdentity("child", token, "original-run", 0, "terminal"),
                    RunStatus.COMPLETED, RunEndReason.MODEL_COMPLETED, false,
                ))
                state.finishRunFinalization(effect.identity, false)
                assertFalse(settled.isCompleted)
                state.finishRunFinalization(effect.identity, true)
                settled.await()
            } finally {
                state.dispose()
            }
        }
    }

    @Test
    fun stopTokenRevocationAndFailedStopWriteDoNotSettleTheOwner() = runBlocking<Unit> {
        withTimeout(5_000) {
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            try {
                val token = checkNotNull(state.tryAcquireForReplacement())
                val owner = Job()
                assertTrue(state.attachGenerationJob(token, owner))
                state.bindRun(token, "original-run")
                val settled = async(start = CoroutineStart.UNDISPATCHED) {
                    state.awaitGenerationSettled(token)
                }
                val stopped = state.stop()
                owner.join()
                state.endGeneration(token)
                assertFalse(state.isCurrentToken(token))
                // La revocación de UI token del Stop NO settlea la espera del owner.
                assertFalse(settled.isCompleted)
                val identity = checkNotNull(stopped.finalizationEffect).identity
                state.finishStopFinalization(ConversationCommand.PersistenceSettled(identity, false))
                assertFalse(settled.isCompleted)
                state.finishStopFinalization(ConversationCommand.PersistenceSettled(identity, true))
                settled.await()
            } finally {
                state.dispose()
            }
        }
    }

    @Test
    fun cancelledWaiterDoesNotReleaseOrChangeTheGeneration() = runBlocking<Unit> {
        withTimeout(5_000) {
            val state = ConversationGenerationState("child", reclaimQueuedAttachments = {})
            try {
                val token = checkNotNull(state.tryAcquireForReplacement())
                val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                    state.awaitGenerationSettled(token)
                }
                assertFalse(waiter.isCompleted)
                waiter.cancelAndJoin()
                // Un waiter cancelado no muta el estado del slot ni del owner.
                assertTrue(state.generating.value)
                assertEquals(token, state.captureUiToken())
                state.endGeneration(token)
            } finally {
                state.dispose()
            }
        }
    }
}
