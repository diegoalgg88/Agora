package com.newoether.agora.data.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDownloadPathsTest {

    private val hfUrl = "https://huggingface.co/org/repo/resolve/abc123/Model-Q4_K_M.gguf?download=true"

    @Test
    fun `relative paths nest under models dir with commit hash`() {
        assertEquals(
            "models/model-a/abc123/file.gguf",
            LocalModelDownloadPaths.relativeFilePath("model-a", "abc123", "file.gguf")
        )
        assertEquals(
            "models/model-a/abc123/file.gguf.part",
            LocalModelDownloadPaths.relativePartialFilePath("model-a", "abc123", "file.gguf")
        )
        assertEquals("models/model-a/abc123", LocalModelDownloadPaths.relativeDirectory("model-a", "abc123"))
    }

    @Test
    fun `path traversal segments are rejected`() {
        assertFalse(LocalModelDownloadPaths.isValidPathSegment(".."))
        assertFalse(LocalModelDownloadPaths.isValidPathSegment("a/b"))
        assertFalse(LocalModelDownloadPaths.isValidPathSegment("a\\b"))
        assertFalse(LocalModelDownloadPaths.isValidPathSegment(""))
        assertTrue(LocalModelDownloadPaths.isValidPathSegment("model-a"))

        val thrown = kotlin.runCatching {
            LocalModelDownloadPaths.requireValidPathSegments("..", "abc", "f.gguf")
        }
        assertTrue(thrown.isFailure)
    }

    @Test
    fun `commit hash and file name extracted from HF resolve URL`() {
        assertEquals("abc123", LocalModelDownloadPaths.commitHashFromUrl(hfUrl))
        assertEquals("Model-Q4_K_M.gguf", LocalModelDownloadPaths.fileNameFromUrl(hfUrl))
        assertEquals("", LocalModelDownloadPaths.commitHashFromUrl("https://example.com/file.gguf"))
        assertEquals("file.gguf", LocalModelDownloadPaths.fileNameFromUrl("https://example.com/file.gguf"))
    }

    @Test
    fun `catalog entry id recovered from relative path only under models dir`() {
        assertEquals("model-a", LocalModelDownloadPaths.catalogEntryIdFromRelativePath("models/model-a/abc123/f.gguf"))
        assertNull(LocalModelDownloadPaths.catalogEntryIdFromRelativePath("other/model-a/abc123/f.gguf"))
        assertNull(LocalModelDownloadPaths.catalogEntryIdFromRelativePath("models/only-two"))
    }

    @Test
    fun `resume headers only when partial exists`() {
        assertTrue(LocalModelDownloadPaths.resumeHeaders(0L).isEmpty())
        val headers = LocalModelDownloadPaths.resumeHeaders(1024L)
        assertEquals("bytes=1024-", headers[LocalModelDownloadPaths.RANGE_HEADER])
        assertEquals("identity", headers[LocalModelDownloadPaths.ACCEPT_ENCODING_HEADER])
    }

    @Test
    fun `append decision follows Content-Range start byte`() {
        // Server honors resume at byte 1024 → append
        assertTrue(LocalModelDownloadPaths.shouldAppendToPartial(1024L, "bytes 1024-2047/2048"))
        // Server ignored Range and restarted at 0 → truncate (no append)
        assertFalse(LocalModelDownloadPaths.shouldAppendToPartial(1024L, "bytes 0-2047/2048"))
        // No header → fresh download
        assertFalse(LocalModelDownloadPaths.shouldAppendToPartial(1024L, null))
        assertFalse(LocalModelDownloadPaths.shouldAppendToPartial(0L, "bytes 0-10/11"))

        assertEquals(1024L, LocalModelDownloadPaths.downloadedBytesAfterConnect(1024L, "bytes 1024-2047/2048"))
        // No Content-Range header → server restarted the full body; count from 0.
        assertEquals(0L, LocalModelDownloadPaths.downloadedBytesAfterConnect(1024L, null))
        assertEquals(0L, LocalModelDownloadPaths.downloadedBytesAfterConnect(1024L, "bytes 0-2047/2048"))
    }

    @Test
    fun `partial file naming round-trips`() {
        assertEquals("f.gguf.part", LocalModelDownloadPaths.partialFileName("f.gguf"))
        assertEquals("f.gguf", LocalModelDownloadPaths.finalFileNameFromPartial("f.gguf.part"))
        assertEquals("f.gguf", LocalModelDownloadPaths.finalFileNameFromPartial("f.gguf"))
        assertTrue(LocalModelDownloadPaths.isPartialFile("f.gguf.part"))
        assertFalse(LocalModelDownloadPaths.isPartialFile("f.gguf"))
    }

    @Test
    fun `completion accepts unknown total but exact known size`() {
        assertTrue(LocalModelDownloadPaths.isCompleteDownload(2048L, 0L))
        assertTrue(LocalModelDownloadPaths.isCompleteDownload(2048L, 2048L))
        assertFalse(LocalModelDownloadPaths.isCompleteDownload(1024L, 2048L))
    }

    @Test
    fun `contentRangeTotal parses 206 and 416 header forms`() {
        assertEquals(1107409472L, LocalModelDownloadPaths.contentRangeTotal("bytes 1024-1107409471/1107409472"))
        assertEquals(1107409472L, LocalModelDownloadPaths.contentRangeTotal("bytes */1107409472"))
        assertNull(LocalModelDownloadPaths.contentRangeTotal(null))
        assertNull(LocalModelDownloadPaths.contentRangeTotal("bytes 0-99/0"))
        assertNull(LocalModelDownloadPaths.contentRangeTotal("garbage"))
    }
}
