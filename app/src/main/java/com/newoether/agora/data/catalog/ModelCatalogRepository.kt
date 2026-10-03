package com.newoether.agora.data.catalog

import android.content.Context
import com.newoether.agora.util.DebugLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves the visible Local Model catalog with a remote → cached → bundled
 * fallback chain. A source that cannot be fetched or parsed falls through to
 * the next one instead of surfacing an empty catalog; only a successfully
 * parsed remote catalog is written to the cache.
 */
class ModelCatalogRepository(
    private val context: Context,
    private val appVersionName: String,
) {
    suspend fun getVisibleEntries(forceRefresh: Boolean = false): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val catalog = if (forceRefresh) {
            (fetchRemote() ?: readCached() ?: readBundled())
        } else {
            (readCached() ?: fetchRemote() ?: readBundled())
        }
        catalog?.let { ModelCatalogParser.visibleEntries(it, appVersionName) } ?: emptyList()
    }

    private suspend fun fetchRemote(): ModelCatalog? = runCatching {
        val connection = URL(HOSTED_CATALOG_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CATALOG_REQUEST_TIMEOUT_MS.toInt()
            connection.readTimeout = CATALOG_REQUEST_TIMEOUT_MS.toInt()
            val code = connection.responseCode
            check(code in 200..299) { "Model catalog fetch failed: HTTP $code" }
            val rawJson = connection.inputStream.bufferedReader().use { it.readText() }
            parseAndCache(rawJson)
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        DebugLog.w("ModelCatalog", "Remote catalog unavailable: ${error.message}")
        null
    }

    private fun readCached(): ModelCatalog? = runCatching {
        val cache = cacheFile(context).takeIf { it.exists() } ?: return null
        parseParsable(cache.readText(), cacheOnSuccess = false)
    }.getOrNull()

    private fun readBundled(): ModelCatalog? = runCatching {
        context.assets.open(CATALOG_FILE_NAME).bufferedReader().use { it.readText() }
    }.getOrNull()?.let { parseParsable(it, cacheOnSuccess = false) }

    private fun parseAndCache(rawJson: String): ModelCatalog? {
        val catalog = parseParsable(rawJson, cacheOnSuccess = false) ?: return null
        runCatching { cacheFile(context).writeText(rawJson) }
        return catalog
    }

    /** An unsupported schema is not a usable source: returns null so the caller falls through. */
    private fun parseParsable(rawJson: String, cacheOnSuccess: Boolean): ModelCatalog? = runCatching {
        val catalog = ModelCatalogParser.parse(rawJson)
        if (catalog.schemaVersion != ModelCatalogParser.SUPPORTED_SCHEMA_VERSION) return null
        if (cacheOnSuccess) runCatching { cacheFile(context).writeText(rawJson) }
        catalog
    }.getOrNull()

    companion object {
        const val HOSTED_CATALOG_URL = "https://raw.githubusercontent.com/diegoalgg88/Agora/main/model_catalog.json"
        const val CATALOG_FILE_NAME = "model_catalog.json"
        private const val CATALOG_REQUEST_TIMEOUT_MS = 15_000L

        private fun cacheFile(context: Context): File = File(context.filesDir, CATALOG_FILE_NAME)
    }
}
