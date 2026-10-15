package com.newoether.agora.api.anthropic

/**
 * Techos documentados de salida (`max_tokens`) de los modelos Claude.
 *
 * El `max_tokens` default se baja a estos valores porque la API rechaza un request cuyo
 * `max_tokens` excede el techo del modelo (Claude 3 acepta a lo más 4096, Claude 3.5 8192).
 *
 * Los valores vienen de la página de cada modelo en platform.claude.com ("Max output"); las
 * entradas pre-4.5 vienen del resumen de modelos tal como estaba cuando esos modelos eran
 * vigentes.
 */
internal object AnthropicOutputLimits {
    /** Ordenado para que un prefijo más largo y específico siempre gane sobre uno más corto. */
    private val ceilings: List<Pair<String, Int>> = listOf(
        "claude-fable-5" to 128_000,
        "claude-mythos-5" to 128_000,
        "claude-opus-5" to 128_000,
        "claude-sonnet-5" to 128_000,
        "claude-opus-4-8" to 128_000,
        "claude-opus-4-7" to 128_000,
        "claude-opus-4-6" to 128_000,
        "claude-sonnet-4-6" to 128_000,
        "claude-opus-4-5" to 64_000,
        "claude-sonnet-4-5" to 64_000,
        "claude-haiku-4-5" to 64_000,
        "claude-opus-4-1" to 32_000,
        "claude-opus-4" to 32_000,
        "claude-sonnet-4" to 64_000,
        "claude-3-7-sonnet" to 64_000,
        "claude-3-5-sonnet" to 8_192,
        "claude-3-5-haiku" to 8_192,
        "claude-3-opus" to 4_096,
        "claude-3-sonnet" to 4_096,
        "claude-3-haiku" to 4_096,
    )

    /**
     * El techo documentado para [modelId], o null cuando Agora no tiene documentación del modelo.
     *
     * Los prefijos de relay (`anthropic/…`) y las versiones con punto (`claude-opus-4.5`) se
     * normalizan primero; un prefijo solo matchea en un boundary de versión, de modo que
     * `claude-opus-4` jamás reclama a `claude-opus-45`.
     */
    fun maxOutputTokens(modelId: String): Int? {
        val model = modelId.trim().lowercase().substringAfterLast('/').replace('.', '-')
        return ceilings.firstOrNull { (prefix, _) ->
            model == prefix || model.startsWith("$prefix-") || model.startsWith("${prefix}@")
        }?.second
    }
}
