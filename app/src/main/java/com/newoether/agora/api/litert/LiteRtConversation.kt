package com.newoether.agora.api.litert

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.newoether.agora.api.LlamaGenerationEvent
import com.newoether.agora.api.LlamaGenerationStopReason
import com.newoether.agora.api.LlamaToolCall
import com.newoether.agora.api.local.LITERTLM_CANCELLED_PREFIX
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Request handed to [LiteRtConversation.generate]: fully mapped SDK values. The conversation
 * wrapper knows nothing about Agora's ChatMessage/ProviderConfig — mapping happens in the
 * request mapper before admission ends. The ConversationConfig itself was already applied by
 * the engine when creating this conversation.
 */
internal class LiteRtGenerationRequest(
    val sendMessage: Message,
    /** Per-request penalties in OpenAI semantics, or null for engine defaults. */
    val repetitionPenaltyConfig: RepetitionPenaltyConfig?,
    val maxOutputToken: Int?,
    val thinkingConfig: ThinkingConfig?,
)

/**
 * One-request wrapper over the SDK [Conversation]. Streams SDK messages into the shared
 * [LlamaGenerationEvent] model so the Local provider maps events exactly like the GGUF path.
 *
 * Uses the SDK's callback overload rather than its Flow variant: the SDK Flow leaves the
 * native streaming running when its collector is cancelled (upstream issue #2718), while the
 * callback form plus [callbackFlow] awaitClose lets us cancel deterministically.
 */
internal class LiteRtConversation(
    private val conversation: Conversation,
) : Closeable {
    companion object {
        private const val TAG = "LiteRtConversation"
    }

    private val closed = AtomicBoolean(false)

    /**
     * Counted down when the native stream reports its terminal callback. The SDK leaves a
     * pending JNI exception if its internal streaming coroutine is torn down while an upcall
     * is still in flight (hard process abort on the next JNI use), so conversation release
     * must wait for the native side to unwind first.
     */
    private val terminal = java.util.concurrent.CountDownLatch(1)

    fun generate(request: LiteRtGenerationRequest): Flow<LlamaGenerationEvent> = callbackFlow {
        val terminalSignalled = AtomicBoolean(false)
        val pendingToolCalls = mutableListOf<com.google.ai.edge.litertlm.ToolCall>()

        fun emitToolCallsIfPending(): Boolean {
            if (pendingToolCalls.isEmpty()) return true
            val gson = com.google.gson.Gson()
            val calls = pendingToolCalls
                .mapIndexed { index, call ->
                    LlamaToolCall(
                        index = index,
                        id = null,
                        name = call.name,
                        arguments = gson.toJson(call.arguments),
                    )
                }
            pendingToolCalls.clear()
            return trySend(LlamaGenerationEvent.ToolCallsCompleted(calls)).isSuccess
        }

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (terminalSignalled.get()) return
                message.channels["thought"]?.takeIf(String::isNotEmpty)?.let {
                    trySend(LlamaGenerationEvent.Thought(it))
                }
                val text = message.contents.toString()
                if (text.isNotEmpty()) {
                    trySend(LlamaGenerationEvent.Text(text))
                }
                if (message.toolCalls.isNotEmpty()) {
                    pendingToolCalls.addAll(message.toolCalls)
                }
            }

            override fun onDone() {
                if (!terminalSignalled.compareAndSet(false, true)) return
                emitToolCallsIfPending()
                val tokenCount = runCatching { conversation.getTokenCount() }.getOrDefault(0)
                trySend(
                    LlamaGenerationEvent.Completed(
                        reason = LlamaGenerationStopReason.EOG,
                        inputTokenCount = tokenCount,
                        outputTokenCount = 0,
                    )
                )
                terminal.countDown()
                close()
            }

            override fun onError(throwable: Throwable) {
                if (!terminalSignalled.compareAndSet(false, true)) return
                DebugLog.e(TAG, "Generation error reported by LiteRT-LM", throwable)
                val cancelled = throwable is java.util.concurrent.CancellationException
                trySend(
                    LlamaGenerationEvent.Failed(
                        message = if (cancelled) {
                            LITERTLM_CANCELLED_PREFIX
                        } else {
                            throwable.message ?: "LiteRT-LM generation failed"
                        },
                        inputTokenCount = 0,
                        outputTokenCount = 0,
                    )
                )
                terminal.countDown()
                close()
            }
        }

        launch(Dispatchers.IO) {
            try {
                conversation.sendMessageAsync(
                    request.sendMessage,
                    callback,
                    repetitionPenaltyConfig = request.repetitionPenaltyConfig,
                    maxOutputToken = request.maxOutputToken,
                    thinkingConfig = request.thinkingConfig,
                )
            } catch (e: Exception) {
                if (!terminalSignalled.get()) {
                    callback.onError(e)
                }
            }
        }

        awaitClose {
            runCatching { conversation.cancelProcess() }
        }
    }

    fun cancel() {
        runCatching { conversation.cancelProcess() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // The SDK's native stream must unwind before the conversation object is deleted:
        // deleting it while an upcall is in flight leaves a pending JNI exception on the
        // thread and the next JNI use aborts the process (hard variant of upstream #2718).
        terminal.await(10, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { conversation.close() }
    }
}
