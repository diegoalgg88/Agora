package com.newoether.agora.api.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.newoether.agora.util.DebugLog
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accelerator backend requested for a LiteRT-LM engine. Part of the resident identity:
 * changing the requested backend reloads the
 * engine rather than mutating resident state.
 */
internal sealed interface LiteRtBackend {
    /** Try GPU first and fall back to CPU when engine initialization fails. */
    data object Auto : LiteRtBackend

    data object Cpu : LiteRtBackend

    data object Gpu : LiteRtBackend

    /**
     * Dedicated neural accelerator. The SDK resolves its delegate libraries from the app's
     * native library directory at engine construction — same identity-participation rules
     * as the other backends. Verified by compilation and contract only: no device with an
     * NPU was available for the on-device smoke pass.
     */
    data class Npu(val nativeLibraryDir: String) : LiteRtBackend
}

/**
 * Thin lifecycle wrapper around the LiteRT-LM SDK [Engine], mirroring the role of
 * [com.newoether.agora.api.LlamaChatEngine] for llama.cpp: this class owns no admission
 * policy and must be constructed/closed only by the process runtime owner inside its
 * FIFO permit.
 *
 * The [Engine] (weights + compiled delegate) is the resident part; [LiteRtConversation]s
 * are created fresh per request because the SDK's cancel path does not roll back internal
 * conversation state (upstream b/450903294) and Agora's branching invalidates any cached
 * conversation prefix anyway.
 */
internal class LiteRtChatEngine(
    val modelPath: String,
    private val backend: LiteRtBackend,
    private val cacheDir: String,
    private val visionCapable: Boolean,
    private val audioCapable: Boolean = false,
    private val mtp: Boolean = false,
) : Closeable {
    companion object {
        private const val TAG = "LiteRtChatEngine"
    }

    private val closed = AtomicBoolean(false)
    private var engine: Engine? = null

    /** Resolved after a successful [load]: the backend the SDK actually initialized with. */
    var activeBackendName: String = ""
        private set

    /**
     * Resolved after a successful [load]: whether speculative decoding is actually active.
     * False when MTP was requested but the bundle rejected it (no drafter weights).
     */
    var activeMtp: Boolean = false
        private set

    fun load(): Boolean {
        if (!File(modelPath).exists()) {
            DebugLog.e(TAG, "Model file not found")
            return false
        }
        // MTP unlock, scoped: the SDK's ExperimentalFlags.enableSpeculativeDecoding is a
        // process global, but the pinned SDK (0.17.1) reads it only inside Engine
        // construction — and this process constructs LiteRT engines exclusively here,
        // under the process-wide local-model FIFO permit, so no other engine creation can
        // observe the value mid-window. Explicit false (not null) for non-MTP models: null
        // means "model's default", and a bundle carrying drafter weights could default-enable
        // MTP against the registered opt-out.
        try {
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableSpeculativeDecoding = mtp
            if (initializeWithBackendFallback()) {
                activeMtp = mtp
                return true
            }
            if (mtp) {
                // Requesting MTP for a bundle without drafter weights makes the SDK throw at
                // initialize(). Failing the whole load would leave the user with a generic
                // "failed to initialize" and no hint that the toggle is the cause, so retry
                // once with speculative decoding explicitly off.
                DebugLog.w(TAG, "Engine init failed with MTP enabled; retrying without MTP")
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableSpeculativeDecoding = false
                if (initializeWithBackendFallback()) {
                    activeMtp = false
                    return true
                }
            }
            close()
            return false
        } finally {
            // Never leave the process global set: any construction outside this scoped
            // window (none today) must see the SDK default, not a stale model choice.
            @OptIn(ExperimentalApi::class)
            ExperimentalFlags.enableSpeculativeDecoding = null
        }
    }

    /**
     * Primary backend first; for [LiteRtBackend.Auto] a failed GPU init falls back to CPU.
     * Returns true once an engine is resident.
     */
    private fun initializeWithBackendFallback(): Boolean {
        val primary = when (backend) {
            LiteRtBackend.Cpu -> Backend.CPU()
            LiteRtBackend.Gpu, LiteRtBackend.Auto -> Backend.GPU()
            is LiteRtBackend.Npu -> Backend.NPU(nativeLibraryDir = backend.nativeLibraryDir)
        }
        initializeOrNull(primary)?.let {
            engine = it
            activeBackendName = primary.name
            DebugLog.d(TAG, "Engine initialized, backend=${primary.name}, mtp=$mtp")
            return true
        }
        if (backend == LiteRtBackend.Auto) {
            DebugLog.w(TAG, "Auto backend fell back to CPU after GPU init failure")
            val cpu = Backend.CPU()
            initializeOrNull(cpu)?.let {
                engine = it
                activeBackendName = cpu.name
                return true
            }
        }
        return false
    }

    /**
     * Builds and initializes one engine, or returns null. A candidate whose initialize() threw
     * is released here: the failed attempt (typically GPU) may already hold delegate memory,
     * and the previous version dropped it unclosed before falling back. Throwable (not
     * Exception) on purpose: a missing/incompatible native library surfaces as
     * UnsatisfiedLinkError, which must degrade to a normal load failure, not kill the process.
     */
    private fun initializeOrNull(sdkBackend: Backend): Engine? {
        var created: Engine? = null
        return try {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = sdkBackend,
                // Vision weights live inside the bundle; only request a vision executor when the
                // registered record says the bundle carries multimodal weights.
                visionBackend = if (visionCapable) Backend.GPU() else null,
                // Audio executor follows the same rule — CPU executor is sufficient for speech
                // encoders; requested only when the record marks the bundle as audio-capable.
                audioBackend = if (audioCapable) Backend.CPU() else null,
                cacheDir = cacheDir,
            )
            val candidate = Engine(config)
            created = candidate
            candidate.initialize()
            candidate
        } catch (e: Throwable) {
            DebugLog.e(TAG, "LiteRT-LM engine init failed on ${sdkBackend.name}", e)
            runCatching { created?.takeIf { it.isInitialized() }?.close() }
            null
        }
    }

    /**
     * Creates a fresh conversation for one request carrying the per-request configuration
     * (system instruction, mapped history, sampler, tools, thinking). The SDK applies the
     * config at conversation creation; the caller owns the returned object and must close it
     * before the FIFO task ends.
     */
    fun createConversation(
        conversationConfig: com.google.ai.edge.litertlm.ConversationConfig,
    ): LiteRtConversation? {
        val current = engine ?: return null
        return try {
            LiteRtConversation(current.createConversation(conversationConfig))
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to create conversation", e)
            null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            engine?.close()
        } catch (e: Exception) {
            DebugLog.w(TAG, "Engine close reported an error", e)
        }
        engine = null
    }
}
