package com.newoether.agora.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataExporterSnapshotSourceContractTest {
    @Test
    fun roomTransactionCapturesTheCompleteConversationGraphWithPagedMessages() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun forEachSnapshotRecord(",
        )
        val transaction = capture.indexOf("database.withTransaction {")
        val conversations = capture.indexOf("chatDao.getAllConversationsList()")
        val runs = capture.indexOf("chatDao.getRunsForConversationSnapshot(conversation.id)")
        val messages = capture.indexOf("forEachMessagePage { page ->")
        val tasks = capture.indexOf("chatDao.getAllTasksList()")
        val loops = capture.indexOf("chatDao.getAllLoopsList()")

        assertTrue(transaction >= 0)
        assertTrue(conversations > transaction)
        assertTrue(runs > conversations)
        assertTrue(messages > runs)
        assertTrue(tasks > messages)
        assertTrue(loops > tasks)
        assertTrue(capture.contains("conversationSettings = conversationSettings[conversation.id]"))
        assertTrue(capture.contains("draftAttachments = conversation.draftAttachments"))
        assertTrue(capture.contains("images = message.images"))
        assertTrue(capture.contains("toolCallJson = message.toolCallJson"))
        assertTrue(capture.contains("attachmentMeta = message.attachmentMeta"))

        val paging = section(
            exporter,
            "private suspend fun forEachMessagePage(",
            "private fun BufferedWriter.writeSnapshotRecord(",
        )
        assertTrue(paging.contains("chatDao.getMessagesPage(afterId, MESSAGE_PAGE_SIZE)"))
        assertTrue(paging.contains("afterId = page.last().id"))
        assertTrue(paging.contains("if (page.size < MESSAGE_PAGE_SIZE) break"))
    }

    @Test
    fun destinationMediaAndFinalArchiveIoStartAfterTheRoomSnapshotReturns() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun forEachSnapshotRecord(",
        )
        assertFalse(capture.contains("openOutputStream("))
        assertFalse(capture.contains("ZipOutputStream("))
        assertFalse(capture.contains("openImageStream("))
        assertFalse(capture.contains("copyStreamToZipEntry("))

        val export = exporter.substringAfter("suspend fun export(")
        val captureCall = export.indexOf(
            "captureConversationSnapshot(settingsManager.conversationSettings.first())",
        )
        // "wt" (write-truncate): sin truncar, exportar sobre un .agora preexistente deja la
        // cola del archivo viejo y todo reader lee el central directory VIEJO.
        val destinationOpen = export.indexOf("context.contentResolver.openOutputStream(uri, \"wt\")")
        val mediaCopy = export.indexOf("buildMediaExportPlan(zip, conversationSpool)")
        val archiveWrite = export.indexOf("writeConversationArchive(")
        assertTrue(captureCall >= 0)
        assertTrue(destinationOpen > captureCall)
        assertTrue(mediaCopy > destinationOpen)
        assertTrue(archiveWrite > mediaCopy)
    }

    @Test
    fun spoolConsumersNeverQueryRoomAndCleanupCoversFailureAndCancellation() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val mediaPlan = section(
            exporter,
            "private suspend fun buildMediaExportPlan(",
            " * Writes the captured Room snapshot",
        )
        val archiveWriter = section(
            exporter,
            "private suspend fun writeConversationArchive(",
            "suspend fun export(",
        )
        assertFalse(mediaPlan.contains("chatDao."))
        val videoCopy = mediaPlan.indexOf("copySource(source, NativeBackupFormat.VIDEO_MEDIA_PREFIX)")
        val metadataRewrite = mediaPlan.indexOf("rewriteAttachmentMetaForExport(")
        assertTrue(videoCopy >= 0 && metadataRewrite > videoCopy)
        assertFalse(archiveWriter.contains("chatDao."))
        assertFalse(exporter.contains("getMessageAttachmentReferencesPage("))
        assertFalse(exporter.contains("getMessageToolMediaReferencesPage("))
        assertFalse(exporter.contains("getRunsForConversation("))

        val capture = section(
            exporter,
            "private suspend fun captureConversationSnapshot(",
            "private suspend fun forEachSnapshotRecord(",
        )
        assertTrue(capture.contains("catch (error: Throwable)"))
        assertTrue(capture.contains("spool.delete()\n            throw error"))
        assertTrue(exporter.contains("finally {\n            conversationSpool?.delete()"))
        assertTrue(
            Regex("currentCoroutineContext\\(\\)\\.ensureActive\\(\\)")
                .findAll(exporter)
                .count() >= 5,
        )
    }

    @Test
    fun manualAndAutomaticExportsReceiveTheProcessDatabase() {
        val exporter = sourceFile(
            "app/src/main/java/com/newoether/agora/data/DataExporter.kt",
        )
        val manager = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/ImportExportManager.kt",
        )
        val backup = sourceFile(
            "app/src/main/java/com/newoether/agora/data/AutoBackupManager.kt",
        )
        val container = sourceFile(
            "app/src/main/java/com/newoether/agora/di/AppContainer.kt",
        )

        assertTrue(exporter.contains("private val database: ChatDatabase"))
        assertTrue(Regex("DataExporter\\(\\s*app,\\s*database,\\s*chatDao,").containsMatchIn(manager))
        assertTrue(backup.contains("private val database: ChatDatabase"))
        assertTrue(Regex("DataExporter\\(\\s*context,\\s*database,\\s*chatDao,").containsMatchIn(backup))
        assertTrue(
            container.contains(
                "AutoBackupManager(appContext, database, settingsManager, chatDao, memoryManager, skillManager)",
            ),
        )
    }

    @Test
    fun contractDefinesTheSnapshotAndTransactionIoBoundary() {
        val contract = sourceFile("development/import-export.md")

        assertTrue(contract.contains("temporary typed JSONL spool"))
        assertTrue(contract.contains("inside one `ChatDatabase` transaction"))
        assertTrue(contract.contains("The transaction performs no destination, ZIP, or media I/O."))
        assertTrue(contract.contains("The spool is deleted on success, failure, and coroutine cancellation."))
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("Missing source section start: $start", startIndex >= 0)
        assertTrue("Missing source section end: $end", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            File(directory, relativePath).takeIf(File::isFile)?.let {
                return it.readText().replace("\r\n", "\n")
            }
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
