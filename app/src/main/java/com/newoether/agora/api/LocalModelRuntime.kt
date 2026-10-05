package com.newoether.agora.api

import com.newoether.agora.api.litert.LiteRtBackend
import com.newoether.agora.api.litert.LiteRtChatEngine
import com.newoether.agora.data.DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES
import com.newoether.agora.data.normalizeLocalModelIdleRetentionMinutes
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal sealed interface LocalModelIdentity {
    val canonicalPath: String

    data class Chat(
        override val canonicalPath: String,
        val nCtx: Int,
    ) : LocalModelIdentity

    data class Embedding(
        override val canonicalPath: String,
    ) : LocalModelIdentity

    /**
     * Embedded LiteRT-LM engine for .litertlm bundles. The requested backend is part of the
     * identity because it can only be applied when the engine is constructed: switching
     * backend means a full unload-before-load replacement, not resident substate mutation.
     * `mtp` follows the same rule — the SDK reads its speculative-decoding toggle only at
     * engine construction, so toggling MTP requires an engine reload.
     */
    data class LiteRtChat(
        override val canonicalPath: String,
        val backend: LiteRtBackend,
        val visionCapable: Boolean,
        val audioCapable: Boolean = false,
        val mtp: Boolean = false,
    ) : LocalModelIdentity
}

/** Fair process-wide admission gate for every embedded llama.cpp operation. */
internal class LocalModelTaskQueue(
    private val onTaskArrived: () -> Unit = {},
    private val onQueueIdle: () -> Unit = {},
) {
    private val permit = Semaphore(1)
    private val stateLock = Any()
    private var submittedTasks = 0

    suspend fun <T> run(block: suspend () -> T): T {
        synchronized(stateLock) {
            submittedTasks++
            onTaskArrived()
        }
        try {
            return permit.withPermit { block() }
        } finally {
            synchronized(stateLock) {
                submittedTasks--
                if (submittedTasks == 0) onQueueIdle()
            }
        }
    }

    fun signalIdleIfEmpty(): Boolean = synchronized(stateLock) {
        if (submittedTasks != 0) return@synchronized false
        onQueueIdle()
        true
    }

    suspend fun runIfIdle(block: () -> Unit): Boolean = permit.withPermit {
        synchronized(stateLock) {
            if (submittedTasks != 0) return@synchronized false
            block()
            true
        }
    }
}

/**
 * Canonical owner of the one llama.cpp model/context that may be resident in this process.
 *
 * A complete Chat request or Embedding batch holds [tasks] until its native work and cleanup have
 * finished. Kotlin's coroutine Semaphore is FIFO, so remote work stays parallel while every local
 * waiter observes one strict order. Resident identity changes always close the old native owner
 * before attempting the new load; a failed replacement therefore leaves no model resident.
 */
internal object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"
    private const val MILLIS_PER_MINUTE = 60_000L

    private sealed interface Resident {
        val identity: LocalModelIdentity

        data class Chat(
            override val identity: LocalModelIdentity.Chat,
            val engine: LlamaChatEngine,
        ) : Resident

        data class Embedding(
            override val identity: LocalModelIdentity.Embedding,
        ) : Resident

        data class LiteRt(
            override val identity: LocalModelIdentity.LiteRtChat,
            val engine: LiteRtChatEngine,
        ) : Resident
    }

    private val lifecycleLock = Any()
    private val tasks = LocalModelTaskQueue(
        onTaskArrived = ::cancelIdleDeadline,
        onQueueIdle = ::startIdleDeadline,
    )
    private var resident: Resident? = null
    private var idleScope: CoroutineScope? = null
    private var idleBindingJob: Job? = null
    private var idleDeadlineJob: Job? = null
    private var idleEpoch = 0L
    private var idleRetentionMinutes = DEFAULT_LOCAL_MODEL_IDLE_RETENTION_MINUTES

    @Volatile
    private var nativeBackendDirectory: String? = null

    @Volatile
    private var liteRtCacheDirectory: String? = null

    @Volatile
    private var activeChatEngine: LlamaChatEngine? = null

    internal fun initialize(nativeLibraryDir: String) {
        require(nativeLibraryDir.isNotBlank()) { "Native library directory must not be blank" }
        val canonicalDirectory = canonicalize(nativeLibraryDir)
        synchronized(lifecycleLock) {
            val currentDirectory = nativeBackendDirectory
            if (currentDirectory != null) {
                check(currentDirectory == canonicalDirectory) {
                    "Local llama backends already initialized from a different directory"
                }
                return
            }
            if (!LlamaEngine.initializeBackends(canonicalDirectory)) {
                DebugLog.e(TAG, "Unable to initialize the Local llama CPU backend")
                return
            }
            nativeBackendDirectory = canonicalDirectory
        }
    }

    /**
     * Binds the process cache directory used by LiteRT-LM engines for delegate cache files
     * (speeds up the second load of the same bundle). Mirrors [initialize]: binding once per
     * process, a different directory afterwards is an invariant violation. LiteRT-LM needs
     * no CPU-backend directory init of its own — its Engine self-initializes from the bundle.
     */
    internal fun initializeLiteRt(cacheDir: String) {
        require(cacheDir.isNotBlank()) { "LiteRT-LM cache directory must not be blank" }
        val canonicalDirectory = canonicalize(cacheDir)
        synchronized(lifecycleLock) {
            val currentDirectory = liteRtCacheDirectory
            if (currentDirectory != null) {
                check(currentDirectory == canonicalDirectory) {
                    "LiteRT-LM cache directory already initialized from a different directory"
                }
                return
            }
            liteRtCacheDirectory = canonicalDirectory
        }
    }

    suspend fun runChat(
        modelPath: String,
        nCtx: Int,
        block: suspend (LlamaChatEngine) -> Unit,
    ): Boolean = tasks.run {
        if (nativeBackendDirectory == null) return@run false
        val identity = LocalModelIdentity.Chat(canonicalize(modelPath), nCtx)
        val current = resident
        val engine = if (current is Resident.Chat && current.identity == identity) {
            current.engine
        } else {
            unloadResident()
            val loaded = LlamaChatEngine(identity.canonicalPath, identity.nCtx)
            if (!loaded.load()) {
                loaded.close()
                return@run false
            }
            resident = Resident.Chat(identity, loaded)
            loaded
        }

        activeChatEngine = engine
        try {
            block(engine)
            true
        } finally {
            activeChatEngine = null
        }
    }

    suspend fun <T> runEmbedding(
        modelPath: String,
        block: () -> T,
    ): T? = tasks.run {
        if (nativeBackendDirectory == null) return@run null
        val identity = LocalModelIdentity.Embedding(canonicalize(modelPath))
        if (resident?.identity != identity) {
            unloadResident()
            if (!LlamaEngine.loadResident(identity.canonicalPath)) return@run null
            resident = Resident.Embedding(identity)
        }
        block()
    }

    /**
     * Admits one complete LiteRT-LM Chat request through the same process FIFO permit as
     * llama.cpp work. Identity is the canonical bundle path plus the requested backend and
     * vision capability: a same-identity request reuses the resident engine, anything else
     * unloads the current resident (either engine family) before loading the new one. The
     * engine stays resident afterwards; conversations are created per request inside [block].
     */
    suspend fun runLiteRtChat(
        modelPath: String,
        backend: LiteRtBackend,
        visionCapable: Boolean,
        audioCapable: Boolean = false,
        mtp: Boolean = false,
        block: suspend (LiteRtChatEngine) -> Unit,
    ): Boolean = tasks.run {
        val cacheDir = liteRtCacheDirectory ?: return@run false
        val identity = LocalModelIdentity.LiteRtChat(canonicalize(modelPath), backend, visionCapable, audioCapable, mtp)
        val current = resident
        val engine = if (current is Resident.LiteRt && current.identity == identity) {
            current.engine
        } else {
            unloadResident()
            val loaded = LiteRtChatEngine(
                modelPath = identity.canonicalPath,
                backend = backend,
                cacheDir = cacheDir,
                visionCapable = visionCapable,
                audioCapable = audioCapable,
                mtp = mtp,
            )
            if (!loaded.load()) {
                loaded.close()
                return@run false
            }
            resident = Resident.LiteRt(identity, loaded)
            loaded
        }

        block(engine)
        true
    }

    fun cancelActiveChat() {
        activeChatEngine?.cancel()
    }

    fun bindIdleRetention(
        retentionMinutes: StateFlow<Int>,
        scope: CoroutineScope,
    ) {
        synchronized(lifecycleLock) {
            if (idleBindingJob != null) return
            idleScope = scope
            idleBindingJob = scope.launch {
                retentionMinutes.collect(::updateIdleRetention)
            }
        }
    }

    private fun updateIdleRetention(minutes: Int) {
        synchronized(lifecycleLock) {
            idleRetentionMinutes = normalizeLocalModelIdleRetentionMinutes(minutes)
            invalidateIdleDeadlineLocked()
        }
        tasks.signalIdleIfEmpty()
    }

    private fun cancelIdleDeadline() {
        synchronized(lifecycleLock) {
            invalidateIdleDeadlineLocked()
        }
    }

    private fun startIdleDeadline() {
        synchronized(lifecycleLock) {
            val scope = idleScope ?: return
            invalidateIdleDeadlineLocked()
            val epoch = idleEpoch
            val delayMillis = idleRetentionMinutes * MILLIS_PER_MINUTE
            idleDeadlineJob = scope.launch {
                if (delayMillis > 0) delay(delayMillis)
                tasks.runIfIdle {
                    synchronized(lifecycleLock) {
                        if (epoch != idleEpoch) return@runIfIdle
                        idleDeadlineJob = null
                        unloadResident()
                    }
                }
            }
        }
    }

    private fun invalidateIdleDeadlineLocked() {
        idleEpoch++
        idleDeadlineJob?.cancel()
        idleDeadlineJob = null
    }

    private fun unloadResident() {
        val description = when (val current = resident ?: return) {
            is Resident.Chat -> {
                current.engine.close()
                "Chat"
            }
            is Resident.Embedding -> {
                LlamaEngine.unloadResident()
                "Embedding"
            }
            is Resident.LiteRt -> {
                current.engine.close()
                "LiteRt"
            }
        }
        resident = null
        DebugLog.d(TAG, "Unloaded resident $description")
    }

    private fun canonicalize(path: String): String {
        val file = File(path)
        return runCatching(file::getCanonicalPath).getOrElse { file.absolutePath }
    }
}
