package com.newoether.agora.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.model.SubagentCompletion
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Suite del upstream (d50903e2) adaptada a las firmas del fork (importer con workManager). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubagentArchiveRoundTripTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: ChatDatabase
    @Before fun setUp() {
        context.deleteDatabase(ChatDatabase.DB_NAME)
        database = ChatDatabase.build(context)
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase(ChatDatabase.DB_NAME)
    }

    @Test fun realExporterAndIncrementalBackupKeepOwnershipButRestoredResultsStayDormant() = runBlocking {
        withContext(Dispatchers.IO) {
            val dao = database.chatDao()
            val result = SubagentCompletion("result", "completed", "Answer", "model")
            dao.upsertConversation(ChatEntity("parent", "Parent", lastUpdated = 1L, dataChangedAt = 1L))
            dao.upsertConversation(ChatEntity("child", "Child", lastUpdated = 1L, dataChangedAt = 1L,
                parentConversationId = "parent", subagentResultJson = SubagentCompletion.encode(result),
                subagentResultId = result.id, subagentResultStatus = result.status))
            val settings = mockk<SettingsManager> {
                every { conversationSettings } returns flowOf(emptyMap())
            }
            val exporter = DataExporter(context, database, database.chatDao(), settings, mockk(), mockk())
            suspend fun export(name: String, baseline: File? = null): File {
                val file = File(temporaryFolder.root, name)
                exporter.export(Uri.fromFile(file), setOf(DataExporter.ExportCategory.CONVERSATIONS), false, baseline)
                assertTrue(file.length() > 0)
                return file
            }
            val first = export("first.agora")
            val child = restored(first).single { it.id == "child" }
            assertEquals("parent", child.parentConversationId)
            assertEquals(result, SubagentCompletion.decode(child.subagentResultJson))
            assertEquals("result", child.subagentDeliveredResultId)
            assertEquals(1, dao.setSubagentResult("child", "parent",
                SubagentCompletion.encode(SubagentCompletion("next", "stopped", "Stopped")), "next", "stopped", 2L))
            val updated = restored(export("incremental.agora", first)).single { it.id == "child" }
            assertEquals("parent", updated.parentConversationId)
            assertEquals("next", updated.subagentResultId)
            assertEquals("next", updated.subagentDeliveredResultId)
            assertEquals("stopped", updated.subagentResultStatus)
        }
    }
    @Test fun archiveOwnershipRejectsMissingSelfNestedCyclicAndMismatchedResults() = runBlocking {
        val parent = ChatEntity("parent", "Parent")
        val child = ChatEntity("child", "Child", parentConversationId = "parent")
        suspend fun valid(rows: List<ChatEntity>, existing: Map<String, ChatEntity> = emptyMap()) =
            runCatching { validateSubagentArchiveOwners(rows, rows.map { it.id }.toSet() + existing.keys) { existing[it] } }.isSuccess
        assertTrue(valid(listOf(parent, child)))
        assertTrue(valid(listOf(child), mapOf(parent.id to parent)))
        assertFalse(valid(listOf(child)))
        assertFalse(valid(listOf(parent.copy(parentConversationId = "parent"))))
        assertFalse(valid(listOf(parent, child, ChatEntity("nested", "Nested", parentConversationId = "child"))))
        assertFalse(valid(listOf(parent.copy(parentConversationId = "child"), child)))
        val result = SubagentCompletion("result", "completed", "Answer")
        assertFalse(valid(listOf(parent, child.copy(subagentResultId = "result"))))
        assertFalse(valid(listOf(parent, child.copy(subagentResultId = "different", subagentResultStatus = result.status,
            subagentResultJson = SubagentCompletion.encode(result)))))
        assertTrue(valid(listOf(parent, child.copy(subagentResultId = result.id, subagentResultStatus = result.status,
            subagentResultJson = SubagentCompletion.encode(result)))))
    }

    private suspend fun restored(file: File): List<ChatEntity> =
        NativeBackupArchive.open(FileInputStream(file).channel).use { archive ->
            NativeConversationGraphSource.open(archive, NativeBackupFormat.CURRENT_VERSION, temporaryFolder.root).use { source ->
                val importer = NativeConversationGraphImporter(database, database.chatDao(), Json { ignoreUnknownKeys = true },
                    NativeConversationMediaRestorer(context, Json), mockk(relaxed = true))
                source.open().use { stream ->
                    importer.readConversationGraphHeaders(stream, DataImporter.ImportStrategy.REPLACE,
                        NativeConversationMediaRestorer.RestoredMedia(emptyMap(), emptyMap(), emptyMap(), emptyList())) { it }
                        .conversations
                }
            }
        }
}
