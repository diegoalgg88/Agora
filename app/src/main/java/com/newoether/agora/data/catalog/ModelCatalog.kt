package com.newoether.agora.data.catalog

import kotlinx.serialization.Serializable

/**
 * Durable wire format of the hosted Local Model catalog (`model_catalog.json`).
 *
 * Schema-compatible with the GPT Mobile catalog (schemaVersion 1) so existing
 * catalogs can be reused. `socToModelFiles` is LiteRT-LM-specific and is
 * deliberately ignored by Agora's GGUF/llama.cpp runtime; it is retained for
 * format compatibility only.
 */
@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 0,
    val models: List<CatalogEntry> = emptyList()
)

@Serializable
data class CatalogEntry(
    val id: String = "",
    val displayName: String = "",
    val downloadUrl: String = "",
    val sizeInBytes: Long = 0L,
    val minRamGb: Int = 0,
    val isGated: Boolean = false,
    val capabilities: CatalogCapabilities = CatalogCapabilities(),
    val supportedAccelerators: List<String> = emptyList(),
    val defaultConfig: CatalogDefaultConfig = CatalogDefaultConfig(),
    val minAppVersion: String = "0.0.0",
    val socToModelFiles: Map<String, CatalogSocVariant> = emptyMap()
)

@Serializable
data class CatalogCapabilities(
    val vision: Boolean = false,
    val tools: Boolean = false,
    val thinking: Boolean = false
)

@Serializable
data class CatalogDefaultConfig(
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 1024,
    val contextSize: Int = 4096
)

/** LiteRT-LM SoC-variant field — parsed for schema compatibility, unused by the GGUF runtime. */
@Serializable
data class CatalogSocVariant(
    val modelFile: String = "",
    val downloadUrl: String = "",
    val commitHash: String = "",
    val sizeInBytes: Long = 0L,
    val contextSize: Int = 0,
    val quantization: String = ""
)
