package com.newoether.agora.data.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelReconcilerTest {

    private fun record(
        id: String,
        status: String = LocalModelStatus.DOWNLOADING,
        commitHash: String = "abc",
        fileName: String = "m.gguf"
    ) = LocalModelRecord(
        catalogEntryId = id,
        commitHash = commitHash,
        fileName = fileName,
        relativeDirectory = LocalModelDownloadPaths.relativeDirectory(id, commitHash),
        status = status
    )

    private fun finalPath(id: String, commitHash: String = "abc", fileName: String = "m.gguf") =
        LocalModelDownloadPaths.relativeFilePath(id, commitHash, fileName)

    private fun partialPath(id: String, commitHash: String = "abc", fileName: String = "m.gguf") =
        LocalModelDownloadPaths.relativePartialFilePath(id, commitHash, fileName)

    @Test
    fun `READY row whose file vanished is deleted`() {
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.READY)),
            diskFiles = emptySet(),
            activeDownloadIds = emptySet()
        )
        assertEquals(listOf(ReconcileAction.DeleteRow("a")), actions)
    }

    @Test
    fun `READY row whose file exists is untouched`() {
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.READY)),
            diskFiles = setOf(finalPath("a")),
            activeDownloadIds = emptySet()
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `DOWNLOADING row without active worker is marked FAILED but partial kept`() {
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.DOWNLOADING)),
            diskFiles = setOf(partialPath("a")),
            activeDownloadIds = emptySet()
        )
        // The partial belongs to the (now dead) download and stays for resume.
        assertEquals(listOf(ReconcileAction.MarkFailed("a")), actions)
    }

    @Test
    fun `DOWNLOADING row with active worker is untouched`() {
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.DOWNLOADING)),
            diskFiles = setOf(partialPath("a")),
            activeDownloadIds = setOf("a")
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `orphan partial of inactive download is deleted`() {
        val actions = LocalModelReconciler.reconcile(
            rows = emptyList(),
            diskFiles = setOf(partialPath("ghost")),
            activeDownloadIds = emptySet()
        )
        assertEquals(listOf(ReconcileAction.DeleteFile(partialPath("ghost"))), actions)
    }

    @Test
    fun `orphan partial of active download is kept`() {
        val actions = LocalModelReconciler.reconcile(
            rows = emptyList(),
            diskFiles = setOf(partialPath("a")),
            activeDownloadIds = setOf("a")
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `orphan non-partial files under models dir are never deleted`() {
        val actions = LocalModelReconciler.reconcile(
            rows = emptyList(),
            diskFiles = setOf(finalPath("ghost")),
            activeDownloadIds = emptySet()
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `FAILED row keeps its partial for resume`() {
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.FAILED)),
            diskFiles = setOf(partialPath("a")),
            activeDownloadIds = emptySet()
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `user cancel plan fails without deleting row or files`() {
        val plan = LocalModelReconciler.planUserCancel()
        assertEquals(LocalModelStatus.FAILED, plan.newStatus)
        assertEquals(false, plan.deleteRow)
        assertEquals(false, plan.deleteFiles)
    }

    @Test
    fun `row for one entry never affects partial of another`() {
        // FAILED row "a" protects only its own partial; "b"'s orphan partial is cleaned.
        val actions = LocalModelReconciler.reconcile(
            rows = listOf(record("a", LocalModelStatus.FAILED)),
            diskFiles = setOf(partialPath("a"), partialPath("b")),
            activeDownloadIds = emptySet()
        )
        assertEquals(listOf(ReconcileAction.DeleteFile(partialPath("b"))), actions)
    }
}
