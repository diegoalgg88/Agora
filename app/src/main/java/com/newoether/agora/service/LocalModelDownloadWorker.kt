package com.newoether.agora.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.catalog.CatalogEntry
import com.newoether.agora.data.local.LocalModelDownloadEntity
import com.newoether.agora.data.localmodel.DownloadErrorClassifier
import com.newoether.agora.data.localmodel.DownloadProgress
import com.newoether.agora.data.localmodel.DownloadRetryClass
import com.newoether.agora.data.localmodel.LocalModelDownloadPaths
import com.newoether.agora.data.localmodel.LocalModelStatus
import com.newoether.agora.MainActivity
import com.newoether.agora.util.DebugLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resumable catalog-model download. One unique work per catalog entry. Writes
 * to a `.part` file with Range resume, publishes progress (bytes + rate + ETA)
 * to WorkManager, and on success finalizes the file and registers the model in
 * the Local provider via [com.newoether.agora.viewmodel.ModelManager].
 */
class LocalModelDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val notificationId: Int = params.id.hashCode()

    override suspend fun doWork(): Result {
        val container = (applicationContext as AgoraApplication).awaitContainer()
            ?: return Result.failure()
        val dao = container.database.localModelDownloadDao()
        val modelManager = container.modelManager

        val catalogEntryId = inputData.getString(KEY_CATALOG_ENTRY_ID)
        val displayName = inputData.getString(KEY_DISPLAY_NAME)
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
        val commitHash = inputData.getString(KEY_COMMIT_HASH)
        val fileName = inputData.getString(KEY_FILE_NAME)
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val modelId = inputData.getString(KEY_MODEL_ID)
        val alias = inputData.getString(KEY_ALIAS)
        val nCtx = inputData.getInt(KEY_N_CTX, 0)
        val temperature = inputData.getFloat(KEY_TEMPERATURE, 0.7f)
        val topP = inputData.getFloat(KEY_TOP_P, 0.9f)
        val maxTokens = inputData.getInt(KEY_MAX_TOKENS, 1024)
        val mmprojUrl = inputData.getString(KEY_MMPROJ_URL)
        val capabilitiesVision = inputData.getBoolean(KEY_CAPABILITIES_VISION, false)
        val format = inputData.getString(KEY_FORMAT) ?: com.newoether.agora.data.LocalChatModelConfig.FORMAT_GGUF
        val topK = inputData.getInt(KEY_TOP_K, 40)

        if (catalogEntryId.isNullOrBlank() || downloadUrl.isNullOrBlank() ||
            commitHash.isNullOrBlank() || fileName.isNullOrBlank() || modelId.isNullOrBlank()
        ) {
            return Result.failure()
        }

        ensureNotificationChannel()
        val seededPercent = seedPercent(catalogEntryId, commitHash, fileName, totalBytes)
        runCatching { setForeground(createForegroundInfo(progress = seededPercent, modelName = displayName)) }

        return withContext(Dispatchers.IO) {
            try {
                dao.updateStatus(catalogEntryId, LocalModelStatus.DOWNLOADING, nowMs())
                downloadFile(
                    catalogEntryId = catalogEntryId,
                    displayName = displayName,
                    downloadUrl = downloadUrl,
                    commitHash = commitHash,
                    fileName = fileName,
                    totalBytes = totalBytes
                )
                var mmprojPath = ""
                // The vision projector is a GGUF-side companion file; .litertlm bundles carry
                // multimodal weights inside the bundle, so litertlm entries never fetch one.
                if (format == com.newoether.agora.data.LocalChatModelConfig.FORMAT_GGUF && !mmprojUrl.isNullOrBlank()) {
                    val mmprojFile = downloadMmproj(catalogEntryId, commitHash, mmprojUrl)
                    mmprojPath = mmprojFile.absolutePath
                }
                markStatus(dao, catalogEntryId, LocalModelStatus.READY)
                registerModel(
                    modelManager = modelManager,
                    modelId = modelId,
                    alias = alias?.takeIf { it.isNotBlank() } ?: displayName ?: modelId,
                    catalogEntryId = catalogEntryId,
                    commitHash = commitHash,
                    fileName = fileName,
                    nCtx = nCtx,
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    mmprojPath = mmprojPath,
                    hasVision = capabilitiesVision,
                    format = format,
                    topK = topK,
                )
                Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                DebugLog.e(TAG, "Download failed: ${error.message}", error)
                val hadProgress = hadPartialProgress(catalogEntryId, commitHash, fileName)
                val classification = DownloadErrorClassifier.classify(error, hadProgress)
                if (DownloadErrorClassifier.shouldRetry(classification, runAttemptCount)) {
                    Result.retry()
                } else {
                    markStatus(dao, catalogEntryId, LocalModelStatus.FAILED)
                    Result.failure(
                        Data.Builder().putString(KEY_ERROR_MESSAGE, error.message ?: "download failed").build()
                    )
                }
            }
        }
    }

    override suspend fun getForegroundInfo() = createForegroundInfo(
        progress = seedPercent(
            catalogEntryId = inputData.getString(KEY_CATALOG_ENTRY_ID),
            commitHash = inputData.getString(KEY_COMMIT_HASH),
            fileName = inputData.getString(KEY_FILE_NAME),
            totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        ),
        modelName = inputData.getString(KEY_DISPLAY_NAME)
    )

    private suspend fun downloadFile(
        catalogEntryId: String,
        displayName: String?,
        downloadUrl: String,
        commitHash: String,
        fileName: String,
        totalBytes: Long
    ) {
        LocalModelDownloadPaths.requireValidPathSegments(catalogEntryId, commitHash, fileName)
        val outputDir = File(storageRoot(), LocalModelDownloadPaths.relativeDirectory(catalogEntryId, commitHash))
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw IOException("Unable to create Local Model directory")
        }
        val outputTmpFile = File(outputDir, LocalModelDownloadPaths.partialFileName(fileName))
        publishSeededProgress(outputTmpFile.length(), totalBytes)

        // A stale partial whose size no longer matches the server's file gets discarded
        // once; the second iteration downloads fresh from byte 0.
        var resumeFromPartial = true
        while (true) {
            val partialLength = outputTmpFile.length()
            val connection = openConnection(
                downloadUrl,
                if (resumeFromPartial) {
                    LocalModelDownloadPaths.resumeHeaders(partialLength)
                } else {
                    mapOf(LocalModelDownloadPaths.ACCEPT_ENCODING_HEADER to LocalModelDownloadPaths.IDENTITY_ENCODING)
                }
            )
            try {
                val responseCode = connection.responseCode
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    // HF's CDN answers 416 without a Content-Range header, so a HEAD probe
                    // is the reliable way to learn the file's true total size.
                    val serverTotal = LocalModelDownloadPaths.contentRangeTotal(connection.getHeaderField("Content-Range"))
                        ?: probeServerTotalBytes(downloadUrl)
                    if (serverTotal != null && serverTotal > 0L) {
                        if (partialLength == serverTotal) {
                            // The partial already holds the whole file (a previous run read the
                            // server to EOF): finalize instead of failing the download.
                            finalizeOutput(outputDir, outputTmpFile, fileName)
                            return
                        }
                        if (partialLength > 0L && resumeFromPartial) {
                            DebugLog.w(TAG, "Discarding unusable partial: $partialLength bytes vs server total $serverTotal")
                            if (!outputTmpFile.delete()) {
                                throw IOException("Unable to discard stale Local Model partial")
                            }
                            resumeFromPartial = false
                            continue
                        }
                    }
                    throw IOException("HTTP error code: $responseCode")
                }
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP error code: $responseCode")
                }

                val contentRange = connection.getHeaderField("Content-Range")
                val serverTotalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    LocalModelDownloadPaths.contentRangeTotal(contentRange)
                } else {
                    connection.contentLengthLong.takeIf { it > 0L }
                }
                val effectiveTotal = serverTotalBytes?.takeIf { it > 0L } ?: totalBytes
                val append = LocalModelDownloadPaths.shouldAppendToPartial(partialLength, contentRange)
                var downloadedBytes = LocalModelDownloadPaths.downloadedBytesAfterConnect(partialLength, contentRange)
                val rateSizeBuffer = mutableListOf<Long>()
                val rateLatencyBuffer = mutableListOf<Long>()

                connection.inputStream.use { inputStream ->
                    FileOutputStream(outputTmpFile, append).use { outputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var lastProgressTs = 0L
                        var deltaBytes = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (isStopped) {
                                throw CancellationException("Local Model download cancelled")
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            deltaBytes += bytesRead

                            val curTs = System.currentTimeMillis()
                            if (curTs - lastProgressTs > PROGRESS_INTERVAL_MS) {
                                var bytesPerMs = 0f
                                if (lastProgressTs != 0L) {
                                    if (rateSizeBuffer.size == RATE_WINDOW) rateSizeBuffer.removeAt(0)
                                    rateSizeBuffer.add(deltaBytes)
                                    if (rateLatencyBuffer.size == RATE_WINDOW) rateLatencyBuffer.removeAt(0)
                                    rateLatencyBuffer.add(curTs - lastProgressTs)
                                    deltaBytes = 0L
                                    bytesPerMs = rateSizeBuffer.sum().toFloat() / rateLatencyBuffer.sum()
                                }
                                var remainingMs = 0f
                                if (bytesPerMs > 0f && effectiveTotal > 0L) {
                                    remainingMs = (effectiveTotal - downloadedBytes) / bytesPerMs
                                }
                                setProgress(
                                    Data.Builder()
                                        .putLong(KEY_RECEIVED_BYTES, downloadedBytes)
                                        .putLong(KEY_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                        .putLong(KEY_REMAINING_MS, remainingMs.toLong())
                                        .build()
                                )
                                val percent = DownloadProgress.percent(downloadedBytes, effectiveTotal)
                                runCatching { setForeground(createForegroundInfo(progress = percent, modelName = displayName)) }
                                lastProgressTs = curTs
                            }
                        }
                    }
                }

                // The server declared its own total (Content-Range/Content-Length) — it, not
                // the catalog's sizeInBytes, is the authority for completion after EOF.
                if (!LocalModelDownloadPaths.isCompleteDownload(outputTmpFile.length(), effectiveTotal)) {
                    throw IOException("Incomplete Local Model download")
                }
                finalizeOutput(outputDir, outputTmpFile, fileName)
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Resolves the file's total size with a HEAD request (used when a 416 lacks Content-Range). */
    private fun probeServerTotalBytes(url: String): Long? = runCatching {
        val connection = openConnection(url, emptyMap())
        try {
            connection.requestMethod = "HEAD"
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@runCatching null
            connection.contentLengthLong.takeIf { it > 0L }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun finalizeOutput(outputDir: File, outputTmpFile: File, fileName: String) {
        val originalFile = File(outputDir, fileName)
        if (originalFile.exists() && !originalFile.delete()) {
            throw IOException("Unable to replace existing Local Model file")
        }
        if (!outputTmpFile.renameTo(originalFile)) {
            throw IOException("Unable to finalize Local Model file")
        }
    }

    /** mmproj (vision projector) download — small, non-resumable, best-effort. */
    private fun downloadMmproj(catalogEntryId: String, commitHash: String, mmprojUrl: String): File {
        val mmprojName = LocalModelDownloadPaths.fileNameFromUrl(mmprojUrl).ifBlank { "mmproj.gguf" }
        LocalModelDownloadPaths.requireValidPathSegments(catalogEntryId, commitHash, mmprojName)
        val outputDir = File(storageRoot(), LocalModelDownloadPaths.relativeDirectory(catalogEntryId, commitHash))
        val target = File(outputDir, mmprojName)
        val connection = openConnection(mmprojUrl, emptyMap())
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("mmproj HTTP error code: $code")
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    private suspend fun registerModel(
        modelManager: com.newoether.agora.viewmodel.ModelManager,
        modelId: String,
        alias: String,
        catalogEntryId: String,
        commitHash: String,
        fileName: String,
        nCtx: Int,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        mmprojPath: String,
        hasVision: Boolean,
        format: String,
        topK: Int,
    ) {
        val finalFile = File(
            storageRoot(),
            LocalModelDownloadPaths.relativeFilePath(catalogEntryId, commitHash, fileName)
        )
        if (!finalFile.exists()) {
            DebugLog.w(TAG, "Finalized model file missing at $finalFile — skipping registration")
            return
        }
        val isLitertlm = format == com.newoether.agora.data.LocalChatModelConfig.FORMAT_LITERTLM
        val config = LocalChatModelConfig(
            modelId = modelId,
            alias = alias,
            localFilePath = finalFile.absolutePath,
            mmprojPath = if (isLitertlm) "" else if (hasVision) mmprojPath else "",
            nCtx = if (nCtx > 0) nCtx else 4096,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            format = format,
            topK = if (isLitertlm) topK.coerceAtLeast(1) else 40,
            // Only litertlm bundles carry vision weights inside the bundle; a GGUF record's
            // vision comes from its mmproj companion, which never sets this flag.
            visionCapable = isLitertlm && hasVision,
        )
        modelManager.addLocalChatModel(config)
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private suspend fun markStatus(
        dao: com.newoether.agora.data.local.LocalModelDownloadDao,
        catalogEntryId: String,
        status: String
    ) {
        dao.updateStatus(catalogEntryId, status, nowMs())
    }

    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.local_model_download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = applicationContext.getString(R.string.local_model_download_notification_channel_description)
            }
        )
    }

    private fun createForegroundInfo(progress: Int, modelName: String? = null): androidx.work.ForegroundInfo {
        val title = applicationContext.getString(R.string.local_model_download_notification_title)
        val content = if (modelName != null) {
            applicationContext.getString(R.string.local_model_download_notification_content, modelName, progress)
        } else {
            applicationContext.getString(R.string.local_model_download_notification_progress, progress)
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress, false)
            .setContentIntent(pendingIntent)
            .build()
        return androidx.work.ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun storageRoot(): File =
        applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir

    private fun seedPercent(
        catalogEntryId: String?,
        commitHash: String?,
        fileName: String?,
        totalBytes: Long
    ): Int {
        if (catalogEntryId.isNullOrBlank() || commitHash.isNullOrBlank() || fileName.isNullOrBlank()) return 0
        return DownloadProgress.percent(partialFileBytes(catalogEntryId, commitHash, fileName), totalBytes)
    }

    private fun hadPartialProgress(catalogEntryId: String, commitHash: String, fileName: String): Boolean =
        partialFileBytes(catalogEntryId, commitHash, fileName) > 0L

    private fun partialFileBytes(catalogEntryId: String, commitHash: String, fileName: String): Long {
        val file = File(
            storageRoot(),
            LocalModelDownloadPaths.relativePartialFilePath(catalogEntryId, commitHash, fileName)
        )
        return file.takeIf { it.exists() }?.length() ?: 0L
    }

    private suspend fun publishSeededProgress(partialLength: Long, totalBytes: Long) {
        if (partialLength <= 0L) return
        setProgress(
            Data.Builder()
                .putLong(KEY_RECEIVED_BYTES, partialLength)
                .putLong(KEY_DOWNLOAD_RATE, 0L)
                .putLong(KEY_REMAINING_MS, 0L)
                .build()
        )
    }

    companion object {
        const val WORK_TAG = "local_model_download"
        const val ID_TAG_PREFIX = "local_model_id:"
        const val KEY_CATALOG_ENTRY_ID = "catalog_entry_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_COMMIT_HASH = "commit_hash"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_ALIAS = "alias"
        const val KEY_N_CTX = "n_ctx"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_TOP_P = "top_p"
        const val KEY_MAX_TOKENS = "max_tokens"
        const val KEY_MMPROJ_URL = "mmproj_url"
        const val KEY_CAPABILITIES_VISION = "capabilities_vision"
        const val KEY_FORMAT = "format"
        const val KEY_TOP_K = "top_k"
        const val KEY_RECEIVED_BYTES = "received_bytes"
        const val KEY_DOWNLOAD_RATE = "download_rate"
        const val KEY_REMAINING_MS = "remaining_ms"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val INITIAL_BACKOFF_SECONDS = 10L
        private const val TAG = "LocalModelDownload"
        private const val CHANNEL_ID = "local_model_downloads"
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val RATE_WINDOW = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        fun idTag(catalogEntryId: String): String = "$ID_TAG_PREFIX$catalogEntryId"

        fun catalogEntryIdFromTag(tag: String): String? =
            tag.takeIf { it.startsWith(ID_TAG_PREFIX) }?.removePrefix(ID_TAG_PREFIX)

        private fun nowMs(): Long = System.currentTimeMillis()

        /**
         * Enqueues one unique download per catalog entry. Input data carries
         * everything the worker needs to both download and auto-register the
         * model on completion — no catalog re-resolution at execution time.
         */
        fun schedule(
            context: Context,
            entry: CatalogEntry,
            modelId: String,
            nCtx: Int,
            temperature: Float,
            topP: Float,
            maxTokens: Int,
            mmprojUrl: String = "",
        ) {
            val commitHash = LocalModelDownloadPaths.commitHashFromUrl(entry.downloadUrl)
            val fileName = LocalModelDownloadPaths.fileNameFromUrl(entry.downloadUrl)
            val inputData = Data.Builder()
                .putString(KEY_CATALOG_ENTRY_ID, entry.id)
                .putString(KEY_DISPLAY_NAME, entry.displayName)
                .putString(KEY_DOWNLOAD_URL, entry.downloadUrl)
                .putString(KEY_COMMIT_HASH, commitHash)
                .putString(KEY_FILE_NAME, fileName)
                .putLong(KEY_TOTAL_BYTES, entry.sizeInBytes)
                .putString(KEY_MODEL_ID, modelId)
                .putString(KEY_ALIAS, entry.displayName)
                .putInt(KEY_N_CTX, nCtx)
                .putFloat(KEY_TEMPERATURE, temperature)
                .putFloat(KEY_TOP_P, topP)
                .putInt(KEY_MAX_TOKENS, maxTokens)
                .putString(KEY_MMPROJ_URL, mmprojUrl)
                .putBoolean(KEY_CAPABILITIES_VISION, entry.capabilities.vision)
                .putString(KEY_FORMAT, entry.format)
                .putInt(KEY_TOP_K, entry.defaultConfig.topK)
                .build()

            val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(inputData)
                .addTag(WORK_TAG)
                .addTag(idTag(entry.id))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                LocalModelDownloadPaths.uniqueWorkName(entry.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context, catalogEntryId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(
                LocalModelDownloadPaths.uniqueWorkName(catalogEntryId)
            )
        }
    }
}
