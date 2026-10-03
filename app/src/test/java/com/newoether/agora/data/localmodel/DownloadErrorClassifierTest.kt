package com.newoether.agora.data.localmodel

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadErrorClassifierTest {

    @Test
    fun `insufficient storage is permanent`() {
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("write failed: ENOSPC (No space left on device)"))
        )
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("not enough space on disk"))
        )
    }

    @Test
    fun `429 and 408 are transient`() {
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(IOException("HTTP error code: 429"))
        )
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(IOException("HTTP code: 408"))
        )
    }

    @Test
    fun `404 401 and other 4xx are permanent`() {
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("HTTP error code: 404"))
        )
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("HTTP error: 401"))
        )
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("HTTP code: 451"))
        )
    }

    @Test
    fun `network timeouts are transient`() {
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(SocketTimeoutException("read timed out"))
        )
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(IOException("connection reset"))
        )
    }

    @Test
    fun `unknown error retry depends on prior progress`() {
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(IOException("weird failure"), hadProgress = true)
        )
        assertEquals(
            DownloadRetryClass.PERMANENT,
            DownloadErrorClassifier.classify(IOException("weird failure"), hadProgress = false)
        )
    }

    @Test
    fun `retry honors max attempts`() {
        assertTrue(DownloadErrorClassifier.shouldRetry(DownloadRetryClass.TRANSIENT, runAttemptCount = 0))
        assertTrue(DownloadErrorClassifier.shouldRetry(DownloadRetryClass.TRANSIENT, runAttemptCount = 3))
        assertFalse(DownloadErrorClassifier.shouldRetry(DownloadRetryClass.TRANSIENT, runAttemptCount = 4))
        assertFalse(DownloadErrorClassifier.shouldRetry(DownloadRetryClass.PERMANENT, runAttemptCount = 0))
    }

    @Test
    fun `causal chain is inspected for classification`() {
        val root = SocketTimeoutException("timed out")
        assertEquals(
            DownloadRetryClass.TRANSIENT,
            DownloadErrorClassifier.classify(IOException("download failed", root))
        )
    }
}

class DownloadProgressTest {

    @Test
    fun `percent is clamped and zero on unknown totals`() {
        assertEquals(0, DownloadProgress.percent(500L, 0L))
        assertEquals(0, DownloadProgress.percent(0L, 1000L))
        assertEquals(50, DownloadProgress.percent(500L, 1000L))
        assertEquals(100, DownloadProgress.percent(2000L, 1000L))
    }

    @Test
    fun `receivedBytes prefers live work progress over stale disk partial`() {
        assertEquals(700L, DownloadProgress.receivedBytes(700L, 300L))
        assertEquals(300L, DownloadProgress.receivedBytes(0L, 300L))
        assertEquals(0L, DownloadProgress.receivedBytes(0L, -5L))
    }

    @Test
    fun `fraction is null when inputs are not positive`() {
        assertEquals(null, DownloadProgress.fraction(0L, 1000L))
        assertEquals(null, DownloadProgress.fraction(100L, 0L))
        assertEquals(0.5f, DownloadProgress.fraction(500L, 1000L)!!, 0.0001f)
    }
}
