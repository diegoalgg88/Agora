package com.newoether.agora.api.aihorde

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Client for the AI Horde native v2 image-generation API (https://aihorde.net/api/v2).
 *
 * The Horde is a crowdsourced network: requests are queued and served by volunteer
 * workers, so generation is asynchronous — submit, poll until done, then download.
 * Authentication uses the `apikey` header; the anonymous key "0000000000" is valid
 * and always queued at the lowest priority. This URL is intentionally NOT derived
 * from [com.newoether.agora.api.openai.AiHordeProvider]'s chat proxy base URL —
 * the image API lives on a different host (aihorde.net vs oai.aihorde.net).
 */
object AiHordeImageClient {

    private const val TAG = "AiHordeImageClient"
    const val BASE_URL = "https://aihorde.net/api/v2"
    const val ANONYMOUS_API_KEY = "0000000000"

    /** Fixed for Play/F-Droid distribution; replacement_filter rewrites flagged prompts. */
    private const val NSFW = false
    private const val REPLACEMENT_FILTER = true

    /** R2 delivery returns stable CDN URLs instead of transient worker webhooks. */
    private const val R2_DELIVERY = true

    /** How often the generation queue is polled while waiting for workers. */
    private const val POLL_INTERVAL_MS = 5_000L

    sealed interface GenerationOutcome {
        /** Image bytes plus the model (checkpoint) name that produced them. */
        data class Success(val bytes: ByteArray, val model: String) : GenerationOutcome

        /** Terminal queue failure — request was rejected or faulted server-side. */
        data class Failure(val code: String, val message: String?) : GenerationOutcome

        /**
         * A censored (e.g. solid black) image returned by an SFW worker whose filter
         * fired near the NSFW boundary. Not a technical failure — the tool should
         * surface this distinctly instead of retrying as if the network had failed.
         */
        data class Censored(val reason: String?) : GenerationOutcome
    }

    /**
     * Submits an async generation request and polls until the image is delivered.
     * [timeoutMs] bounds the total submit + poll + download wall clock; the anonymous
     * queue can take minutes, so callers should pass Constants.IMAGE_GENERATION_TIMEOUT_MS.
     */
    suspend fun generate(
        prompt: String,
        apiKey: String,
        model: String,
        width: Int,
        height: Int,
        timeoutMs: Long,
        pollIntervalMs: Long = POLL_INTERVAL_MS,
    ): GenerationOutcome = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.ifBlank { ANONYMOUS_API_KEY }
        val deadline = System.currentTimeMillis() + timeoutMs

        val submitBody = buildJsonObject {
            put("prompt", prompt)
            put("nsfw", NSFW)
            put("r2", R2_DELIVERY)
            put("replacement_filter", REPLACEMENT_FILTER)
            put(
                "params",
                buildJsonObject {
                    put("width", width)
                    put("height", height)
                    put("steps", 30)
                },
            )
            if (model.isNotBlank()) {
                put("models", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(model))))
            }
        }.toString()

        val submitResponse = HttpClient.postTextResponse(
            "$BASE_URL/generate/async",
            submitBody,
            mapOf("apikey" to effectiveKey, "Client-Agent" to "Agora:1.0:standalone"),
        )
        if (!submitResponse.isSuccessful) {
            val detail = try {
                Json.parseToJsonElement(submitResponse.body).jsonObject["message"]?.jsonPrimitive?.content
            } catch (e: Exception) {
                null
            } ?: submitResponse.body.takeIf(String::isNotBlank)
            return@withContext GenerationOutcome.Failure("submit_failed", "HTTP ${submitResponse.code}${detail?.let { ": $it" } ?: ""}")
        }

        val id = try {
            Json.parseToJsonElement(submitResponse.body).jsonObject["id"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse submit response", e)
            null
        } ?: return@withContext GenerationOutcome.Failure("no_job_id", "The Horde returned no job id.")

        pollUntilDone(id, effectiveKey, deadline, pollIntervalMs) ?: return@withContext pollTimeoutOutcome()
    }

    /**
     * Polls the check endpoint until k_done / terminal failure. On success fetches
     * the final status, downloads the R2 image and distinguishes censored results.
     */
    private suspend fun pollUntilDone(
        id: String,
        apiKey: String,
        deadline: Long,
        pollIntervalMs: Long,
    ): GenerationOutcome? = withContext(Dispatchers.IO) {
        while (System.currentTimeMillis() < deadline) {
            try {
                val check = HttpClient.getTextResponse(
                    "$BASE_URL/generate/check/$id",
                    mapOf("apikey" to apiKey, "Client-Agent" to "Agora:1.0:standalone"),
                )
                if (!check.isSuccessful) {
                    return@withContext GenerationOutcome.Failure("check_failed", "HTTP ${check.code}")
                }
                // The real /generate/check/{id} response has no "state" string field:
                // it returns done/faulted as plain booleans alongside queue counters
                // (finished, processing, restarted, waiting, wait_time, queue_position,
                // kudos, is_possible). Censorship is only knowable from the per-generation
                // "censored" flag in the final /generate/status/{id} response.
                val checkJson = Json.parseToJsonElement(check.body).jsonObject
                val faulted = checkJson["faulted"]?.jsonPrimitive?.booleanOrNull ?: false
                if (faulted) {
                    return@withContext GenerationOutcome.Failure(
                        "faulted",
                        "The Horde could not complete this request.",
                    )
                }
                val done = checkJson["done"]?.jsonPrimitive?.booleanOrNull ?: false
                if (done) {
                    return@withContext fetchFinalImage(id, apiKey, deadline)
                }
                // waiting / processing — keep polling.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "Poll error for job $id", e)
                // Transient poll failure — fall through to the delay and retry
                // unless the deadline has passed.
            }
            delay(pollIntervalMs)
        }
        null
    }

    /** Fetches the final generation status and downloads the delivered image. */
    private suspend fun fetchFinalImage(
        id: String,
        apikey: String,
        deadline: Long,
    ): GenerationOutcome = withContext(Dispatchers.IO) {
        val status = HttpClient.getTextResponse(
            "$BASE_URL/generate/status/$id",
            mapOf("apikey" to apikey, "Client-Agent" to "Agora:1.0:standalone"),
        )
        if (!status.isSuccessful) {
            return@withContext GenerationOutcome.Failure("status_failed", "HTTP ${status.code}")
        }
        val generations = try {
            Json.parseToJsonElement(status.body).jsonObject["generations"]?.jsonArray
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse status response", e)
            null
        } ?: return@withContext GenerationOutcome.Failure("no_generations", "Status returned no generations.")

        val first = generations.firstOrNull()?.jsonObject
            ?: return@withContext GenerationOutcome.Failure("no_generations", "Status returned no generations.")
        val censored = first["censored"]?.jsonPrimitive?.booleanOrNull ?: false
        val reason = first["censored_message"]?.jsonPrimitive?.content
        if (censored) return@withContext GenerationOutcome.Censored(reason)

        val url = first["img"]?.jsonPrimitive?.content
            ?: first["r2_url"]?.jsonPrimitive?.content
            ?: return@withContext GenerationOutcome.Failure("no_image_url", "Generation has no image URL.")
        val usedModel = first["model"]?.jsonPrimitive?.content ?: ""

        val bytes = HttpClient.getBytes(url) // R2 CDN; no API key header needed.
            ?: return@withContext GenerationOutcome.Failure("download_failed", null)

        GenerationOutcome.Success(bytes, usedModel)
    }

    private fun pollTimeoutOutcome(): GenerationOutcome =
        GenerationOutcome.Failure(
            "timeout",
            "The Horde queue did not finish in time. Anonymous keys have the lowest priority — try again or register a personal key.",
        )

    /**
     * Lists available image models (checkpoint names with at least one worker online).
     * Nulled-out entries are filtered client-side by type == "image". This catalog is
     * a dedicated namespace — it must NEVER be written into settings.availableModels
     * (that map feeds the chat model picker).
     */
    suspend fun fetchImageModelNames(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = HttpClient.getTextResponse(
                "$BASE_URL/status/models",
                mapOf("apikey" to apiKey.ifBlank { ANONYMOUS_API_KEY }, "Client-Agent" to "Agora:1.0:standalone"),
            )
            if (!response.isSuccessful) return@withContext emptyList<String>()
            Json.parseToJsonElement(response.body).jsonArray
                .mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                    val name = obj["name"]?.jsonPrimitive?.content
                    // count = number of workers currently serving this model. A model with
                    // zero online workers still appears in this catalog but every generation
                    // against it will simply time out, so it's filtered out here.
                    val onlineWorkers = obj["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    if (type == "image" && onlineWorkers > 0 && !name.isNullOrBlank()) name else null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "fetchImageModelNames failed", e)
            emptyList()
        }
    }
}
