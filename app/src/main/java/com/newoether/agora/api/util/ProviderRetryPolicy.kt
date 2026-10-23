package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.providerHttpError

/** One initial provider request plus five retries. */
internal object ProviderRetryPolicy {
    const val MAX_RETRIES = 5
    const val MAX_ATTEMPTS = MAX_RETRIES + 1

    private val retryableUpstreamMessages = listOf(
        "failed to generate",
        "the server response could not be read",
        "rate limit",
        "rate_limit",
        "too many requests",
        "overloaded",
        "temporarily unavailable",
    )
    private val permanentErrorIdentifiers = setOf(
        "insufficient_quota", "quota_exhausted", "exhausted_quota",
        "insufficient_balance", "insufficient_funds", "insufficient_credit", "insufficient_credits",
        "credit_balance_too_low", "credits_exhausted", "billing_hard_limit_reached", "billing_not_active",
        "usage_limit_reached", "spend_limit_reached",
        "invalid_api_key", "authentication_error", "unauthenticated", "permission_denied",
        "permission_error", "unauthorized", "forbidden", "401", "403",
        "invalid_request", "invalid_request_error", "invalid_argument", "bad_request",
        "context_length_exceeded", "model_not_found",
        "unsupported_parameter", "unsupported_value", "content_policy_violation",
    )
    private val permanentErrorMessages = listOf(
        "insufficient quota", "quota exhausted", "quota has been exhausted",
        "exceeded your current quota", "exceeded your monthly quota",
        "insufficient balance", "insufficient funds", "insufficient credit",
        "credit balance is too low", "credit balance too low", "credits exhausted",
        "out of credits", "billing hard limit", "billing limit reached",
        "billing is not active", "spending limit reached", "spend limit reached",
        "invalid api key", "incorrect api key", "invalid authentication",
        "authentication failed", "permission denied", "invalid request",
        "unsupported parameter", "unsupported value", "context length exceeded",
        "maximum context length", "model not found", "content policy violation",
    )
    private val retryableErrorIdentifiers = setOf(
        "rate_limit_error", "rate_limit_exceeded", "too_many_requests", "overloaded_error",
        "server_error", "internal_error", "internal_server_error", "unavailable",
        "resource_exhausted", "failed_to_generate", "429", "500", "502", "503", "504", "529",
    )

    /** Delay before retry number [retryNumber], where valid retries are 1 through 5. */
    fun delayMillis(retryNumber: Int): Long = when (retryNumber) {
        in 1..3 -> 5_000L
        in 4..5 -> 30_000L
        else -> error("Invalid retry number: $retryNumber")
    }

    /** Relay outcome used when the upstream accepted a request but failed to produce a generation. */
    fun isFailedToGenerateOutcome(raw: String?): Boolean =
        raw?.contains("failed to generate", ignoreCase = true) == true

    /** Exact response-read failure eligible for Provider replay, including partial output. */
    fun isUnreadableServerResponseFailure(raw: String?): Boolean =
        raw?.contains("the server response could not be read", ignoreCase = true) == true

    fun isUnexpectedEndFailure(raw: String?): Boolean =
        raw?.let { text ->
            text.contains("unexpected end of stream", ignoreCase = true) ||
                text.contains("unexpected end of file", ignoreCase = true)
        } == true

    /** Provider text that identifies a transient upstream failure safe for the existing retry loop. */
    fun isRetryableUpstreamFailure(raw: String?): Boolean =
        raw?.let { text ->
            retryableUpstreamMessages.any { message -> text.contains(message, ignoreCase = true) }
        } == true

    /** Response-read failures must consume the existing retry budget before becoming terminal. */
    fun requiresResponseReadRetry(error: GenerationError?): Boolean {
        return when (error) {
            is GenerationError.SseParse -> true
            is GenerationError.Api -> isUnreadableServerResponseFailure(error.message)
            is GenerationError.Network -> isUnreadableServerResponseFailure(error.message)
            is GenerationError.Unknown -> {
                val seen = mutableSetOf<Throwable>()
                var failure: Throwable? = error.cause
                var matches = false
                while (failure != null && seen.add(failure)) {
                    if (failure is kotlinx.coroutines.CancellationException) return false
                    matches = matches || isUnreadableServerResponseFailure(failure.localizedMessage)
                    failure = failure.cause
                }
                matches
            }
            else -> false
        }
    }

    /** Other permanent account/request failures win over generic transient wording. */
    fun isPermanentApiFailure(error: GenerationError.Api): Boolean =
        listOfNotNull(error.code, error.type).any {
            it.trim().lowercase() in permanentErrorIdentifiers
        } || permanentErrorMessages.any { error.message.contains(it, ignoreCase = true) }

    fun isPermanentHttpFailure(statusCode: Int, body: String?): Boolean =
        statusCode in setOf(401, 403) || isPermanentApiFailure(providerHttpError(statusCode, body))

    /** Inspect decoded fields too, since a JSON error message can contain Unicode escapes. */
    fun isResponseReadHttpFailure(statusCode: Int, body: String?): Boolean =
        isUnreadableServerResponseFailure(body) ||
            requiresResponseReadRetry(providerHttpError(statusCode, body))

    /** Other permanent HTTP failures retain their existing non-retryable semantics. */
    fun shouldRetryHttp(statusCode: Int, body: String?, retryableStatusCodes: Set<Int>): Boolean =
        isResponseReadHttpFailure(statusCode, body) ||
            (!isPermanentHttpFailure(statusCode, body) &&
                (statusCode in retryableStatusCodes || isRetryableUpstreamFailure(body)))

    /** Response-read failures take priority; all other API errors need transient evidence. */
    fun shouldRetryStreamError(error: GenerationError?): Boolean =
        requiresResponseReadRetry(error) || when (error) {
            is GenerationError.Api -> !isPermanentApiFailure(error) && (
                listOfNotNull(error.code, error.type).any {
                    it.trim().lowercase() in retryableErrorIdentifiers
                } || isRetryableUpstreamFailure(error.message)
            )
            is GenerationError.Network ->
                isRetryableUpstreamFailure(error.message) || isUnexpectedEndFailure(error.message)
            else -> false
        }
}
