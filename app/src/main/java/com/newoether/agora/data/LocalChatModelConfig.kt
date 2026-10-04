package com.newoether.agora.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LocalChatModelConfig(
    val id: String = UUID.randomUUID().toString(),
    val modelId: String,
    val alias: String,
    val localFilePath: String = "",
    val mmprojPath: String = "",
    val nCtx: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 4096,
    /**
     * Backing engine for this record. GGUF records run through the embedded llama.cpp path;
     * `litertlm` records run through the embedded LiteRT-LM engine (.litertlm bundles).
     * Older persisted records decode with the GGUF default.
     */
    val format: String = FORMAT_GGUF,
    /**
     * LiteRT-LM only: which accelerator backend the engine requests. "auto" tries GPU and
     * falls back to CPU at load time. Part of resident identity — changing it reloads the engine.
     */
    val backend: String = BACKEND_AUTO,
    /**
     * LiteRT-LM only: sampler top-K, required positive by the LiteRT-LM SamplerConfig.
     * GGUF ignores this value.
     */
    val topK: Int = 40,
    /**
     * LiteRT-LM only: the bundle itself carries multimodal (vision) weights. When true the
     * engine configures a vision backend and image contents are forwarded to the SDK.
     */
    val visionCapable: Boolean = false,
) {
    companion object {
        const val FORMAT_GGUF = "gguf"
        const val FORMAT_LITERTLM = "litertlm"
        const val BACKEND_AUTO = "auto"
        const val BACKEND_CPU = "cpu"
        const val BACKEND_GPU = "gpu"

        /** Default Agora-side history budget for newly registered .litertlm models. */
        const val LITERTLM_DEFAULT_NCTX = 4096
    }
}
