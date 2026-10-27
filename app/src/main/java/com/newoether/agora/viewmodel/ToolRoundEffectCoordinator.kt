package com.newoether.agora.viewmodel

import com.newoether.agora.model.RunEffect
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Identity and ordering gate for one validated Provider tool outcome.
 *
 * Tool progress remains presentation-only. This coordinator accepts exactly one batch effect,
 * closes it into exactly one commit effect, runs that durable effect to an unambiguous result,
 * and returns continuation authority only after the success result is accepted.
 *
 * Los rechazos del mailbox viajan como null (el caller cierra el Run como STOPPED por el path
 * ordinario): una CancellationException manual aquí queda pendiente ante ART en el resume de
 * UndispatchedCoroutine y aborta el proceso entero (mismo defecto del SIGABRT 2026-10-26).
 */
internal class ToolRoundEffectCoordinator(
    private val callbacks: GenerationCallbacks,
) {
    private var batchEffect: RunEffect.ExecuteToolBatch? = null
    private var commitEffect: RunEffect.CommitToolRound? = null

    suspend fun acceptValidatedBatch(
        providerOutcomeIdentity: RunEffectIdentity,
    ): RunEffect.ExecuteToolBatch? {
        check(batchEffect == null && commitEffect == null) {
            "A Provider pass cannot overlap an unfinished tool effect"
        }
        val accepted = callbacks.onToolBatchRequested(providerOutcomeIdentity)
        if (accepted == null) {
            DebugLog.w("AgoraVM", "Provider pass ${providerOutcomeIdentity.effectId} is no longer current")
            return null
        }
        check(
            accepted.identity == providerOutcomeIdentity.copy(
                effectId = "tool-batch-${providerOutcomeIdentity.effectId}",
            ),
        ) { "Provider outcome returned the wrong tool-batch identity" }
        batchEffect = accepted
        return accepted
    }

    fun requireBatchEffect(): RunEffect.ExecuteToolBatch = checkNotNull(batchEffect) {
        "Tool execution requires an accepted batch effect"
    }

    suspend fun completeBatch(
        batchIdentity: RunEffectIdentity,
    ): RunEffect.CommitToolRound? {
        val activeBatch = requireBatchEffect()
        check(activeBatch.identity == batchIdentity) {
            "A stale tool batch cannot produce authoritative results"
        }
        val accepted = callbacks.onToolBatchCompleted(batchIdentity)
        if (accepted == null) {
            DebugLog.w("AgoraVM", "Tool batch ${batchIdentity.effectId} is no longer current")
            return null
        }
        check(
            accepted.identity == batchIdentity.copy(
                effectId = "tool-round-${batchIdentity.effectId}",
            ),
        ) { "Tool batch completion returned the wrong commit identity" }
        batchEffect = null
        commitEffect = accepted
        return accepted
    }

    fun requireCommitEffect(): RunEffect.CommitToolRound = checkNotNull(commitEffect) {
        "A complete tool batch must own one protocol-round commit effect"
    }

    suspend fun commitRound(
        persist: suspend (RunEffectIdentity) -> Unit,
    ): RunEffect.ContinueProviderPass? {
        val activeCommit = requireCommitEffect()
        try {
            // Once issued, this transaction must reach one result even if Stop concurrently
            // cancels the generation. The Room predicate decides whether it may still write.
            withContext(NonCancellable) {
                persist(activeCommit.identity)
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try {
                    callbacks.onToolRoundCommitted(activeCommit.identity, false)
                } catch (_: Exception) {
                    // Preserve the original durable failure; runtime disposal can reject feedback.
                }
            }
            throw error
        }

        val continuation = withContext(NonCancellable) {
            callbacks.onToolRoundCommitted(activeCommit.identity, true)
        }
        commitEffect = null
        if (
            continuation !is RunEffect.ContinueProviderPass ||
            continuation.identity != activeCommit.identity
        ) {
            DebugLog.w("AgoraVM", "Tool round ${activeCommit.identity.effectId} is no longer current")
            return null
        }
        return continuation
    }
}
