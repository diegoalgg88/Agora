package com.newoether.agora.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingBatchValidationTest {
    @Test
    fun uniformBatch_withoutExpectedDimensions_isValid() {
        val batch = listOf(FloatArray(768), FloatArray(768), FloatArray(768))
        assertTrue(validateEmbeddingBatch(batch, expectedDimensions = null))
    }

    @Test
    fun mixedDimensions_areRejected() {
        val batch = listOf(FloatArray(768), FloatArray(1024), FloatArray(768))
        assertFalse(validateEmbeddingBatch(batch, expectedDimensions = null))
    }

    @Test
    fun mismatchWithDeclaredDimensions_isRejected() {
        val batch = listOf(FloatArray(1024), FloatArray(1024))
        assertFalse(validateEmbeddingBatch(batch, expectedDimensions = 1536))
    }

    @Test
    fun matchingDeclaredDimensions_isValid() {
        val batch = listOf(FloatArray(1536), FloatArray(1536))
        assertTrue(validateEmbeddingBatch(batch, expectedDimensions = 1536))
    }

    @Test
    fun zeroDimension_isRejected() {
        val batch = listOf(FloatArray(0), FloatArray(0))
        assertFalse(validateEmbeddingBatch(batch, expectedDimensions = null))
    }

    @Test
    fun nullEntry_isRejected() {
        val batch = listOf<FloatArray?>(FloatArray(768), null)
        assertFalse(validateEmbeddingBatch(batch, expectedDimensions = null))
    }

    @Test
    fun emptyBatch_isRejected() {
        assertFalse(validateEmbeddingBatch(emptyList(), expectedDimensions = null))
    }

    @Test
    fun singleNullBatch_isRejected() {
        assertFalse(validateEmbeddingBatch(listOf<FloatArray?>(null), expectedDimensions = null))
    }
}
