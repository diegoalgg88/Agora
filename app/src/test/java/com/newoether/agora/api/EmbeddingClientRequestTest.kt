package com.newoether.agora.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingClientRequestTest {
    @Test
    fun request_withoutDimensions_omitsTheKey() {
        val encoded = EmbeddingClient.json.encodeToString(
            EmbeddingRequest(input = "hello", model = "text-embedding-3-small")
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        assertFalse(obj.containsKey("dimensions"))
        assertEquals("hello", obj["input"]!!.jsonPrimitive.content)
        assertEquals("text-embedding-3-small", obj["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun request_withDimensions_includesTheKey() {
        val encoded = EmbeddingClient.json.encodeToString(
            EmbeddingRequest(input = "hello", model = "text-embedding-3-small", dimensions = 1536)
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        assertTrue(obj.containsKey("dimensions"))
        assertEquals(1536, obj["dimensions"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun batchRequest_withoutDimensions_omitsTheKey() {
        val encoded = EmbeddingClient.json.encodeToString(
            BatchEmbeddingRequest(input = listOf("a", "b"), model = "embed-v1")
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        assertFalse(obj.containsKey("dimensions"))
    }

    @Test
    fun batchRequest_withDimensions_includesTheKey() {
        val encoded = EmbeddingClient.json.encodeToString(
            BatchEmbeddingRequest(input = listOf("a"), model = "embed-v1", dimensions = 768)
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        assertTrue(obj.containsKey("dimensions"))
        assertEquals(768, obj["dimensions"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun legacyJson_withoutDimensionsField_stillDecodes() {
        val legacy = """{"input":"hello","model":"text-embedding-3-small"}"""
        val decoded = EmbeddingClient.json.decodeFromString<EmbeddingRequest>(legacy)
        assertEquals("hello", decoded.input)
        assertEquals(null, decoded.dimensions)
    }
}
