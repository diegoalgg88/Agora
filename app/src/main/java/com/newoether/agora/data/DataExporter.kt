package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.newoether.agora.automation.LoopPolicy
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

class DataExporter(
    private val context: Context,
    private val database: ChatDatabase,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val skillManager: SkillManager,
    private val baselineFile: File? = null,
) {
    companion object {
        /** Bounds entity/string expansion while exporting databases with large chat histories. */
        private const val MESSAGE_PAGE_SIZE = 64
        private const val SNAPSHOT_PREFIX = "agora-export-snapshot-"
        private const val SNAPSHOT_SUFFIX = ".jsonl"
        private const val SNAPSHOT_CONVERSATION = "C"
        private const val SNAPSHOT_RUN = "R"
        private const val SNAPSHOT_MESSAGE = "M"
        private const val SNAPSHOT_TASK = "T"
        private const val SNAPSHOT_LOOP = "L"
    }

    enum class ExportCategory(val manifestKey: String) {
        CONVERSATIONS("conversations"),
        MEMORIES("memories"),
        SYSTEM_PROMPTS("system_prompts"),
        SETTINGS("settings"),
        API_KEYS("api_keys");

        companion object {
            fun fromManifestKey(key: String): ExportCategory? =
                entries.find { it.manifestKey == key }
        }
    }

    @Serializable
    private data class ExportManifest(
        @SerialName("agora_export_version") val version: Int,
        @SerialName("app_version") val appVersion: String,
        @SerialName("exported_at") val exportedAt: String,
        val categories: List<String>,
        @SerialName("has_api_keys") val hasApiKeys: Boolean = false
    )

    @Serializable
    private data class ExportChatEntity(
        val id: String,
        val title: String,
        val lastUpdated: Long,
        /** Watermark del contenido de datos; el backup incremental raw-copia conversaciones sin cambios. */
        val dataChangedAt: Long = 0L,
        val selectedBranchesJson: String? = null,
        val systemPromptId: String? = null,
        val modelId: String? = null,
        val taskId: String? = null,
        val origin: String = "user",
        val graduated: Boolean = false,
        val selectedRunBranchesJson: String? = null,
        val draftText: String = "",
        val draftAttachments: String? = null,
        val conversationSettings: ConversationSettings? = null,
        /** Conversación pineada (sección Pinned del drawer); se exporta y restaura. */
        val isPinned: Boolean = false,
        val todoJson: String? = null,
    )

    @Serializable
    private data class ExportRunEntity(
        val id: String,
        val conversationId: String,
        val parentRunId: String? = null,
        val status: String,
        val startedAt: Long,
        val lastCheckpointAt: Long,
        val stopRequestedAt: Long? = null,
        val endedAt: Long? = null,
        val endReason: String? = null,
        val currentPass: Int = 0,
        val legacyAmbiguous: Boolean = false,
    )

    @Serializable
    private data class ExportTaskEntity(
        val id: String,
        val name: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val modelId: String? = null,
        val cronExpr: String,
        /** One-shot fire instant; null for a recurring (cron) task. */
        val runAt: Long? = null,
        val createdAt: Long,
        val lastRunAt: Long? = null
    )

    @Serializable
    private data class ExportLoopEntity(
        val conversationId: String,
        val intervalMs: Long,
        val prompt: String? = null,
        val cycleCount: Int = 0,
        /** New v2 archives always emit the bounded default for legacy null values. */
        val maxCycles: Int? = LoopPolicy.DEFAULT_MAX_CYCLES,
    )

    @Serializable
    private data class ExportMessageEntity(
        val id: String,
        val conversationId: String,
        val parentId: String? = null,
        val text: String,
        val images: List<String> = emptyList(),
        val thoughts: String? = null,
        val thoughtTitle: String? = null,
        val tokenCount: Int = 0,
        val inputTokenCount: Int? = null,
        val cachedInputTokenCount: Int? = null,
        val cacheWriteInputTokenCount: Int? = null,
        val uncachedInputTokenCount: Int? = null,
        val outputTokenCount: Int? = null,
        val reasoningTokenCount: Int? = null,
        val status: String = "SUCCESS",
        val participant: String = "MODEL",
        val timestamp: Long,
        val thoughtTimeMs: Long? = null,
        val modelName: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val runId: String,
        val runSequence: Long,
        val consumedAtPass: Int? = null,
    )

    data class ExportResult(
        val imagesExported: Int = 0,
        val missingResourceCount: Int = 0,
    )

    private data class MediaExportPlan(
        val messageImages: Map<String, List<String>>,
        val messageAttachmentMeta: Map<String, String?>,
        val draftAttachments: Map<String, String?>,
        val sourceToArchiveEntry: Map<String, String>,
        val copiedImageCount: Int,
        val missingResourceCount: Int,
    )

    private fun openImageStream(imgUri: String): java.io.InputStream? {
        val uri = Uri.parse(imgUri)
        // Handle content:// and file:// URIs
        if (uri.scheme == "content" || uri.scheme == "file") {
            return try { context.contentResolver.openInputStream(uri) } catch (_: Exception) { null }
        }
        // Handle bare file paths (from processImages)
        val file = java.io.File(imgUri)
        if (file.exists()) return try { file.inputStream() } catch (_: Exception) { null }
        return null
    }

    private fun mediaSourceKey(source: String): String {
        val raw = source.removePrefix("file://")
        return when {
            source.startsWith("content://") -> source
            source.startsWith("file://") || File(raw).exists() ->
                runCatching { File(raw).canonicalPath }.getOrElse { File(raw).absolutePath }
            else -> source
        }
    }

    private fun archiveMediaEntry(prefix: String, source: String): String {
        val extension = runCatching {
            val withoutQuery = source.substringBefore('?').substringBefore('#')
            withoutQuery.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        }.getOrNull()
        return buildString {
            append(prefix)
            append(UUID.randomUUID())
            if (extension != null) {
                append('.')
                append(extension)
            }
        }
    }

    private suspend fun forEachMessagePage(
        block: suspend (List<MessageEntity>) -> Unit,
    ) {
        var afterId: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = chatDao.getMessagesPage(afterId, MESSAGE_PAGE_SIZE)
            if (page.isEmpty()) break
            block(page)
            afterId = page.last().id
            if (page.size < MESSAGE_PAGE_SIZE) break
        }
    }

    private fun BufferedWriter.writeSnapshotRecord(type: String, json: String) {
        write(type)
        write('\t'.code)
        write(json)
        newLine()
    }

    private suspend fun captureConversationSnapshot(
        conversationSettings: Map<String, ConversationSettings>,
    ): File {
        val spool = File.createTempFile(SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX, context.cacheDir)
        try {
            database.withTransaction {
                spool.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val conversations = chatDao.getAllConversationsList()
                    for (conversation in conversations) {
                        currentCoroutineContext().ensureActive()
                        writer.writeSnapshotRecord(
                            SNAPSHOT_CONVERSATION,
                            Json.encodeToString(
                                ExportChatEntity(
                                    id = conversation.id,
                                    title = conversation.title,
                                    lastUpdated = conversation.lastUpdated,
                                    dataChangedAt = conversation.dataChangedAt,
                                    selectedBranchesJson = conversation.selectedBranchesJson,
                                    systemPromptId = conversation.systemPromptId,
                                    modelId = conversation.modelId,
                                    taskId = conversation.taskId,
                                    origin = conversation.origin,
                                    graduated = conversation.graduated,
                                    selectedRunBranchesJson = conversation.selectedRunBranchesJson,
                                    draftText = conversation.draftText,
                                    draftAttachments = conversation.draftAttachments,
                                    conversationSettings = conversationSettings[conversation.id],
                                    isPinned = conversation.isPinned,
                                    todoJson = conversation.todoJson,
                                ),
                            ),
                        )
                        for (run in chatDao.getRunsForConversationSnapshot(conversation.id)) {
                            currentCoroutineContext().ensureActive()
                            writer.writeSnapshotRecord(
                                SNAPSHOT_RUN,
                                Json.encodeToString(
                                    ExportRunEntity(
                                        id = run.id,
                                        conversationId = run.conversationId,
                                        parentRunId = run.parentRunId,
                                        status = run.status.name,
                                        startedAt = run.startedAt,
                                        lastCheckpointAt = run.lastCheckpointAt,
                                        stopRequestedAt = run.stopRequestedAt,
                                        endedAt = run.endedAt,
                                        endReason = run.endReason?.name,
                                        currentPass = run.currentPass,
                                        legacyAmbiguous = run.legacyAmbiguous,
                                    ),
                                ),
                            )
                        }
                    }

                    forEachMessagePage { page ->
                        for (message in page) {
                            currentCoroutineContext().ensureActive()
                            writer.writeSnapshotRecord(
                                SNAPSHOT_MESSAGE,
                                Json.encodeToString(
                                    ExportMessageEntity(
                                        id = message.id,
                                        conversationId = message.conversationId,
                                        parentId = message.parentId,
                                        text = message.text,
                                        images = message.images,
                                        thoughts = message.thoughts,
                                        thoughtTitle = message.thoughtTitle,
                                        tokenCount = message.tokenCount,
                                        inputTokenCount = message.inputTokenCount,
                                        cachedInputTokenCount = message.cachedInputTokenCount,
                                        cacheWriteInputTokenCount = message.cacheWriteInputTokenCount,
                                        uncachedInputTokenCount = message.uncachedInputTokenCount,
                                        outputTokenCount = message.outputTokenCount,
                                        reasoningTokenCount = message.reasoningTokenCount,
                                        status = message.status.name,
                                        participant = message.participant.name,
                                        timestamp = message.timestamp,
                                        thoughtTimeMs = message.thoughtTimeMs,
                                        modelName = message.modelName,
                                        toolCallJson = message.toolCallJson,
                                        attachmentMeta = message.attachmentMeta,
                                        runId = message.runId,
                                        runSequence = message.runSequence,
                                        consumedAtPass = message.consumedAtPass,
                                    ),
                                ),
                            )
                        }
                    }

                    for (task in chatDao.getAllTasksList()) {
                        currentCoroutineContext().ensureActive()
                        writer.writeSnapshotRecord(
                            SNAPSHOT_TASK,
                            Json.encodeToString(
                                ExportTaskEntity(
                                    id = task.id,
                                    name = task.name,
                                    prompt = task.prompt,
                                    systemPrompt = task.systemPrompt,
                                    modelId = task.modelId,
                                    cronExpr = task.cronExpr,
                                    runAt = task.runAt,
                                    createdAt = task.createdAt,
                                    lastRunAt = task.lastRunAt,
                                ),
                            ),
                        )
                    }

                    for (loop in chatDao.getAllLoopsList()) {
                        currentCoroutineContext().ensureActive()
                        val sanitized = sanitizeImportedLoop(loop)
                        writer.writeSnapshotRecord(
                            SNAPSHOT_LOOP,
                            Json.encodeToString(
                                ExportLoopEntity(
                                    conversationId = sanitized.conversationId,
                                    intervalMs = sanitized.intervalMs,
                                    prompt = sanitized.prompt,
                                    cycleCount = sanitized.cycleCount,
                                    maxCycles = sanitized.maxCycles,
                                ),
                            ),
                        )
                    }
                }
            }
            return spool
        } catch (error: Throwable) {
            spool.delete()
            throw error
        }
    }

    private suspend fun forEachSnapshotRecord(
        spool: File,
        type: String,
        block: suspend (String) -> Unit,
    ) {
        spool.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                val separator = line.indexOf('\t')
                if (separator != 1) throw IOException("Invalid export snapshot record")
                if (line.startsWith(type)) block(line.substring(separator + 1))
            }
        }
    }

    /** Copies one media stream directly into the archive without a heap-sized byte array. */
    private fun copyStreamToZipArchiveEntry(
        zip: ZipArchiveOutputStream,
        entryName: String,
        input: InputStream?,
    ): Boolean {
        if (input == null) return false
        return input.use { stream ->
            zip.putArchiveEntry(ZipArchiveEntry(entryName))
            try {
                stream.copyTo(zip) > 0L
            } finally {
                zip.closeArchiveEntry()
            }
        }
    }

    private fun ZipArchiveOutputStream.writeJsonToken(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }

    private suspend fun buildMediaExportPlan(
        zip: ZipArchiveOutputStream,
        spool: File,
    ): MediaExportPlan {
        val messageImages = mutableMapOf<String, List<String>>()
        val messageAttachmentMeta = mutableMapOf<String, String?>()
        val draftAttachments = mutableMapOf<String, String?>()
        val sourceToArchiveEntry = mutableMapOf<String, String>()
        var copiedImageCount = 0
        var missingResourceCount = 0

        fun copySource(source: String, prefix: String): String? {
            if (source.isBlank()) return null
            val sourceKey = mediaSourceKey(source)
            sourceToArchiveEntry[sourceKey]?.let { return it }
            val entry = archiveMediaEntry(prefix, source)
            val copied = try {
                copyStreamToZipArchiveEntry(
                    zip = zip,
                    entryName = entry,
                    input = openImageStream(source),
                )
            } catch (_: Exception) {
                false
            }
            return entry.takeIf { copied }?.also { sourceToArchiveEntry[sourceKey] = it }
        }

        forEachSnapshotRecord(spool, SNAPSHOT_MESSAGE) { raw ->
            val message = Json.decodeFromString<ExportMessageEntity>(raw)
            val meta = message.attachmentMeta?.let {
                runCatching { Json.decodeFromString<AttachmentMeta>(it) }.getOrNull()
            }
            meta?.items
                ?.asSequence()
                ?.filter { it.type == "video" && !it.unavailable }
                ?.mapNotNull { it.originalUri }
                ?.forEach { source ->
                    copySource(source, NativeBackupFormat.VIDEO_MEDIA_PREFIX)
                }

            if (message.images.isNotEmpty()) {
                val oldToNewImageIndex = mutableMapOf<Int, Int>()
                val archivedImages = buildList {
                    message.images.forEachIndexed { oldIndex, source ->
                        copySource(source, NativeBackupFormat.IMAGE_MEDIA_PREFIX)?.let { entry ->
                            oldToNewImageIndex[oldIndex] = size
                            add(entry)
                            copiedImageCount++
                        }
                    }
                }
                messageImages[message.id] = archivedImages
                messageAttachmentMeta[message.id] =
                    NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                        raw = message.attachmentMeta,
                        originalImageSources = message.images,
                        oldToNewImageIndex = oldToNewImageIndex,
                        archiveEntryForSource = { source ->
                            sourceToArchiveEntry[mediaSourceKey(source)]
                        },
                        onMissingResource = { missingResourceCount++ },
                    )
            } else if (message.attachmentMeta != null) {
                messageAttachmentMeta[message.id] =
                    NativeBackupMediaPolicy.rewriteAttachmentMetaForExport(
                        raw = message.attachmentMeta,
                        originalImageSources = emptyList(),
                        oldToNewImageIndex = emptyMap(),
                        archiveEntryForSource = { source ->
                            sourceToArchiveEntry[mediaSourceKey(source)]
                        },
                        onMissingResource = { missingResourceCount++ },
                    )
            }

            NativeBackupMediaPolicy.toolImagePaths(message.toolCallJson).forEach { source ->
                if (copySource(source, NativeBackupFormat.IMAGE_MEDIA_PREFIX) != null) {
                    copiedImageCount++
                }
            }
        }

        forEachSnapshotRecord(spool, SNAPSHOT_CONVERSATION) { raw ->
            val conversation = Json.decodeFromString<ExportChatEntity>(raw)
            val attachments = conversation.draftAttachments?.let { encoded ->
                runCatching {
                    Json.decodeFromString<List<SelectedAttachment>>(encoded)
                }.getOrNull()
            } ?: return@forEachSnapshotRecord
            val archived = NativeBackupMediaPolicy.rewriteDraftAttachmentsForExport(
                attachments = attachments,
                archiveEntryForSource = { source ->
                    copySource(source, NativeBackupFormat.DRAFT_MEDIA_PREFIX)
                },
                onMissingResource = { missingResourceCount++ },
            )
            draftAttachments[conversation.id] = archived
                .takeIf(List<SelectedAttachment>::isNotEmpty)
                ?.let { Json.encodeToString(it) }
        }

        return MediaExportPlan(
            messageImages = messageImages,
            messageAttachmentMeta = messageAttachmentMeta,
            draftAttachments = draftAttachments,
            sourceToArchiveEntry = sourceToArchiveEntry,
            copiedImageCount = copiedImageCount,
            missingResourceCount = missingResourceCount,
        )
    }

    /**
     * Writes the captured Room snapshot without performing any further database reads.
     *
     * v5 layout: one `conv/items/<hex-id>.json` per conversation (conversations/runs/messages/loops
     * del mismo id juntos), `conv/tasks.json` y `conv/index.json`. Conversaciones cuyo watermark
     * `dataChangedAt` coincide con el baseline se raw-copian (entrada + media) en vez de
     * re-escribirse: el backup incremental solo paga lo que cambió.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun writeConversationArchive(
        zip: ZipArchiveOutputStream,
        spool: File,
        mediaPlan: MediaExportPlan,
        baseline: NativeBackupV5Baseline? = null,
        unchangedConversationIds: Set<String> = emptySet(),
    ) {
        val conversations = mutableListOf<JsonObject>()
        val runs = mutableListOf<JsonObject>()
        val messages = mutableListOf<JsonObject>()
        val tasks = mutableListOf<JsonObject>()
        val loops = mutableListOf<JsonObject>()

        forEachSnapshotRecord(spool, SNAPSHOT_CONVERSATION) { raw ->
            val conversation = Json.decodeFromString<ExportChatEntity>(raw)
            conversations += Json.parseToJsonElement(
                Json.encodeToString(
                    conversation.copy(
                        draftAttachments = mediaPlan.draftAttachments[conversation.id],
                    ),
                ),
            ).jsonObject
        }
        forEachSnapshotRecord(spool, SNAPSHOT_RUN) { runs += Json.parseToJsonElement(it).jsonObject }
        forEachSnapshotRecord(spool, SNAPSHOT_MESSAGE) { raw ->
            val message = Json.decodeFromString<ExportMessageEntity>(raw)
            messages += Json.parseToJsonElement(
                Json.encodeToString(
                    message.copy(
                        images = mediaPlan.messageImages[message.id] ?: emptyList(),
                        toolCallJson = NativeBackupMediaPolicy.rewriteToolImagePathsForExport(
                            raw = message.toolCallJson,
                            archiveEntryForSource = { source ->
                                mediaPlan.sourceToArchiveEntry[mediaSourceKey(source)]
                            },
                        ),
                        attachmentMeta = mediaPlan.messageAttachmentMeta[message.id],
                    ),
                ),
            ).jsonObject
        }
        forEachSnapshotRecord(spool, SNAPSHOT_TASK) { tasks += Json.parseToJsonElement(it).jsonObject }
        forEachSnapshotRecord(spool, SNAPSHOT_LOOP) { loops += Json.parseToJsonElement(it).jsonObject }

        NativeBackupV5Writer.write(
            zip,
            conversations,
            runs,
            messages,
            tasks,
            loops,
            baseline,
            unchangedConversationIds,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun export(
        uri: Uri,
        categories: Set<ExportCategory>,
        includeApiKeys: Boolean,
        baselineFile: File? = null,
        onProgress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.IO) {
        val appInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appVersion = appInfo.versionName ?: "unknown"
        val exportedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val manifest = ExportManifest(
            version = NativeBackupFormat.CURRENT_VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            categories = categories.map { it.manifestKey },
            hasApiKeys = includeApiKeys && categories.contains(ExportCategory.API_KEYS)
        )

        var imagesExportedTotal = 0
        var missingResourceCount = 0
        val totalSteps = categories.size + 1 // +1 for manifest
        var completed = 0
        fun step() { completed++; onProgress(completed.toFloat() / totalSteps) }

        val conversationSpool = if (ExportCategory.CONVERSATIONS in categories) {
            captureConversationSnapshot(settingsManager.conversationSettings.first())
        } else {
            null
        }
        try {
            // "wt" (write-truncate): SAF abre "rw" por defecto y NO trunca — exportar sobre un
            // .agora preexistente dejaba la cola del archivo viejo (con su central directory)
            // tras el contenido nuevo, y todo reader leía el zip VIEJO: "Error reading zip".
            val rawOutput = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Could not open the selected backup destination")
            rawOutput.use { raw ->
                val zip = ZipArchiveOutputStream(BufferedOutputStream(raw))

                // Manifest
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.MANIFEST_ENTRY))
                Json.encodeToStream(manifest, zip)
                zip.closeArchiveEntry()
                step()

                // Conversations
                if (conversationSpool != null) {
                    // Watermarks actuales del spool → conversaciones sin cambio vs el baseline.
                    val currentWatermarks = linkedMapOf<String, Long>()
                    forEachSnapshotRecord(conversationSpool, SNAPSHOT_CONVERSATION) { raw ->
                        val conversation = Json.decodeFromString<ExportChatEntity>(raw)
                        currentWatermarks[conversation.id] = conversation.dataChangedAt
                    }
                    val baseline = NativeBackupV5Baseline.openOrNull(baselineFile)
                    try {
                        val unchangedConversationIds =
                            baseline?.unchangedConversationIds(currentWatermarks).orEmpty()
                        val mediaPlan = buildMediaExportPlan(zip, conversationSpool)
                        imagesExportedTotal += mediaPlan.copiedImageCount
                        missingResourceCount += mediaPlan.missingResourceCount
                        writeConversationArchive(
                            zip = zip,
                            spool = conversationSpool,
                            mediaPlan = mediaPlan,
                            baseline = baseline,
                            unchangedConversationIds = unchangedConversationIds,
                        )
                    } finally {
                        baseline?.close()
                    }
                    step()
                }

            // Memories
            if (ExportCategory.MEMORIES in categories) {
                val activeMemory = memoryManager.getActiveMemory()
                if (activeMemory.isNotEmpty()) {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/active_memory.md"))
                    zip.write(activeMemory.toByteArray())
                    zip.closeArchiveEntry()
                }
                for (file in memoryManager.listFiles()) {
                    val content = memoryManager.readFile(file.name)
                    zip.putArchiveEntry(ZipArchiveEntry("memories/memory_db/${file.name}"))
                    zip.write(content.toByteArray())
                    zip.closeArchiveEntry()
                }
                val metaJson = memoryManager.getMetaJson()
                if (metaJson != "{}") {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/memory_db/memory_meta.json"))
                    zip.write(metaJson.toByteArray())
                    zip.closeArchiveEntry()
                }
                for (file in skillManager.listFiles()) {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/skill_db/${file.name}"))
                    zip.write(skillManager.readFile(file.name).toByteArray())
                    zip.closeArchiveEntry()
                }
                val skillMetaJson = skillManager.getMetaJson()
                if (skillMetaJson != "{}") {
                    zip.putArchiveEntry(ZipArchiveEntry("memories/skill_db/skill_meta.json"))
                    zip.write(skillMetaJson.toByteArray())
                    zip.closeArchiveEntry()
                }
                step()
            }

            // System Prompts
            if (ExportCategory.SYSTEM_PROMPTS in categories) {
                val prompts = settingsManager.systemPrompts.first()
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SYSTEM_PROMPTS_ENTRY))
                Json.encodeToStream(prompts, zip)
                zip.closeArchiveEntry()
                step()
            }

            // Settings
            if (ExportCategory.SETTINGS in categories) {
                val fontFile = settingsManager.customFontPath.first()
                    .takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                if (fontFile != null) {
                    zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.CUSTOM_FONT_ENTRY))
                    fontFile.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                }
                val settings = PortableSettingsArchive.toJsonObject(
                    sm = settingsManager,
                    customFontIncluded = fontFile != null,
                )
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SETTINGS_ENTRY))
                Json.encodeToStream(settings, zip)
                zip.closeArchiveEntry()
                step()
            }

            // API Keys (opt-in)
            if (includeApiKeys && ExportCategory.API_KEYS in categories) {
                val keys = NativeBackupSecretsPolicy.capture(settingsManager)
                zip.putArchiveEntry(ZipArchiveEntry(NativeBackupFormat.SECRETS_ENTRY))
                Json.encodeToStream(keys, zip)
                zip.closeArchiveEntry()
                step()
            }

            zip.finish()
            zip.flush()
        }

            onProgress(1f)
            ExportResult(
                imagesExported = imagesExportedTotal,
                missingResourceCount = missingResourceCount,
            )
        } finally {
            conversationSpool?.delete()
        }
    }
}
