package com.newoether.agora.data

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.newoether.agora.data.catalog.CatalogEntry
import com.newoether.agora.data.local.LocalModelDownloadDao
import com.newoether.agora.data.local.LocalModelDownloadEntity
import com.newoether.agora.data.localmodel.LocalModelDownloadPaths
import com.newoether.agora.data.localmodel.LocalModelReconciler
import com.newoether.agora.data.localmodel.LocalModelStatus
import com.newoether.agora.data.localmodel.ReconcileAction
import com.newoether.agora.service.LocalModelDownloadWorker
import com.newoether.agora.util.DebugLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

/**
 * Orchestration owner for catalog-driven Local Model downloads: durable row
 * lifecycle around the WorkManager worker, user cancel/delete, and startup
 * reconciliation of rows vs disk. File and row bookkeeping are the ONLY
 * responsibilities — download bytes belong to the worker, model registration
 * belongs to ModelManager (invoked by the worker).
 */
class LocalModelDownloadManager(
    private val context: Context,
    private val dao: LocalModelDownloadDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun observeRows(): Flow<List<LocalModelDownloadEntity>> = dao.observeAll()

    fun observeWorkInfos(): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(LocalModelDownloadWorker.WORK_TAG)

    suspend fun getRow(catalogEntryId: String): LocalModelDownloadEntity? = dao.getById(catalogEntryId)

    suspend fun startDownload(
        entry: CatalogEntry,
        modelId: String,
        nCtx: Int,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        mmprojUrl: String = "",
    ) = withContext(ioDispatcher) {
        val existing = dao.getById(entry.id)
        if (existing?.status == LocalModelStatus.DOWNLOADING && entry.id in activeDownloadIds()) {
            return@withContext
        }
        val commitHash = LocalModelDownloadPaths.commitHashFromUrl(entry.downloadUrl)
        val fileName = LocalModelDownloadPaths.fileNameFromUrl(entry.downloadUrl)
        LocalModelDownloadPaths.requireValidPathSegments(entry.id, commitHash, fileName)
        val now = System.currentTimeMillis()
        dao.upsert(
            LocalModelDownloadEntity(
                catalogEntryId = entry.id,
                commitHash = commitHash,
                fileName = fileName,
                relativeDirectory = LocalModelDownloadPaths.relativeDirectory(entry.id, commitHash),
                totalBytes = entry.sizeInBytes,
                status = LocalModelStatus.DOWNLOADING,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
        )
        LocalModelDownloadWorker.schedule(
            context = context,
            entry = entry,
            modelId = modelId,
            nCtx = nCtx,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            mmprojUrl = mmprojUrl,
        )
    }

    suspend fun cancelDownload(catalogEntryId: String) = withContext(ioDispatcher) {
        LocalModelDownloadWorker.cancel(context, catalogEntryId)
        val row = dao.getById(catalogEntryId) ?: return@withContext
        val plan = LocalModelReconciler.planUserCancel()
        if (plan.deleteFiles) {
            File(storageRoot(), row.relativeDirectory).deleteRecursively()
        }
        if (plan.deleteRow) {
            dao.deleteById(catalogEntryId)
        } else if (row.status == LocalModelStatus.DOWNLOADING) {
            dao.updateStatus(catalogEntryId, plan.newStatus, System.currentTimeMillis())
        }
    }

    /** Deletes the downloaded file, its row, and cancels any in-flight work. */
    suspend fun deleteModel(catalogEntryId: String) = withContext(ioDispatcher) {
        LocalModelDownloadPaths.requireValidPathSegments(catalogEntryId)
        LocalModelDownloadWorker.cancel(context, catalogEntryId)
        val row = dao.getById(catalogEntryId)
        if (row != null) {
            File(storageRoot(), row.relativeDirectory).deleteRecursively()
            dao.deleteById(catalogEntryId)
            File(storageRoot(), LocalModelDownloadPaths.MODELS_DIR)
                .resolve(catalogEntryId)
                .takeIf { it.isDirectory && it.list().isNullOrEmpty() }
                ?.delete()
        }
    }

    suspend fun reconcile() = withContext(ioDispatcher) {
        if (context.getExternalFilesDir(null) == null) {
            DebugLog.w(TAG, "Skipping Local Model reconcile: external storage unavailable")
            return@withContext
        }
        val rows = dao.getAll().map {
            com.newoether.agora.data.localmodel.LocalModelRecord(
                catalogEntryId = it.catalogEntryId,
                commitHash = it.commitHash,
                fileName = it.fileName,
                relativeDirectory = it.relativeDirectory,
                status = it.status
            )
        }
        val actions = LocalModelReconciler.reconcile(
            rows = rows,
            diskFiles = listModelFiles(),
            activeDownloadIds = activeDownloadIds()
        )
        val now = System.currentTimeMillis()
        actions.forEach { action ->
            when (action) {
                is ReconcileAction.DeleteRow -> dao.deleteById(action.catalogEntryId)
                is ReconcileAction.MarkFailed -> dao.updateStatus(action.catalogEntryId, LocalModelStatus.FAILED, now)
                is ReconcileAction.DeleteFile -> File(storageRoot(), action.relativePath).delete()
            }
        }
    }

    fun diskPartialBytes(row: LocalModelDownloadEntity): Long {
        val file = File(
            storageRoot(),
            LocalModelDownloadPaths.relativePartialFilePath(row.catalogEntryId, row.commitHash, row.fileName)
        )
        return file.takeIf { it.exists() }?.length() ?: 0L
    }

    fun absoluteFilePath(row: LocalModelDownloadEntity): String? {
        val file = File(
            storageRoot(),
            LocalModelDownloadPaths.relativeFilePath(row.catalogEntryId, row.commitHash, row.fileName)
        )
        return file.takeIf { it.exists() }?.absolutePath
    }

    private fun storageRoot(): File = context.getExternalFilesDir(null) ?: context.filesDir

    private fun listModelFiles(): Set<String> {
        val root = storageRoot()
        val modelsDir = File(root, LocalModelDownloadPaths.MODELS_DIR)
        if (!modelsDir.exists()) return emptySet()
        return modelsDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
    }

    private suspend fun activeDownloadIds(): Set<String> {
        val infos = runCatching { observeWorkInfos().first() }.getOrDefault(emptyList())
        return infos
            .filter { !it.state.isFinished }
            .mapNotNull { info -> info.tags.firstNotNullOfOrNull(LocalModelDownloadWorker::catalogEntryIdFromTag) }
            .toSet()
    }

    private companion object {
        const val TAG = "LocalModelDownload"
    }
}

/** Convenience projection: catalogEntryId → live WorkInfo for that download. */
fun Flow<List<WorkInfo>>.byCatalogEntryId(): Flow<Map<String, WorkInfo>> =
    mapNotNull { infos ->
        infos.associate { info ->
            LocalModelDownloadWorker.catalogEntryIdFromTag(info.tags.firstOrNull() ?: "") to info
        }.filterKeys { it != null }.mapKeys { it.key!! }
    }
