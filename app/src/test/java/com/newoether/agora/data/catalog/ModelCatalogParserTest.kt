package com.newoether.agora.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogParserTest {

    private val catalogJson = """
        {
          "schemaVersion": 1,
          "models": [
            {
              "id": "model-a",
              "displayName": "Model A",
              "downloadUrl": "https://huggingface.co/org/repo/resolve/abc123/file.gguf",
              "sizeInBytes": 1073741824,
              "minRamGb": 4,
              "isGated": false,
              "capabilities": { "vision": true, "tools": true, "thinking": false },
              "defaultConfig": { "topK": 40, "topP": 0.95f, "temperature": 0.7f, "maxTokens": 2048, "contextSize": 8192 },
              "minAppVersion": "1.0.0"
            },
            {
              "id": "model-b",
              "displayName": "Model B",
              "downloadUrl": "https://example.com/b.gguf",
              "sizeInBytes": 52428800,
              "minRamGb": 2,
              "isGated": true,
              "capabilities": {},
              "defaultConfig": {},
              "minAppVersion": "99.0.0",
              "socToModelFiles": {
                "Tensor G1": { "modelFile": "b.task", "downloadUrl": "", "commitHash": "deadbeef", "sizeInBytes": 1, "contextSize": 2048, "quantization": "q4" }
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses full catalog with defaults and soc variants`() {
        val catalog = ModelCatalogParser.parse(catalogJson)
        assertEquals(1, catalog.schemaVersion)
        assertEquals(2, catalog.models.size)

        val a = catalog.models[0]
        assertEquals("model-a", a.id)
        assertEquals(1073741824L, a.sizeInBytes)
        assertTrue(a.capabilities.vision)
        assertTrue(a.capabilities.tools)
        assertEquals(false, a.capabilities.thinking)
        assertEquals(8192, a.defaultConfig.contextSize)
        assertEquals(0.7f, a.defaultConfig.temperature, 0.0001f)

        val b = catalog.models[1]
        assertTrue(b.isGated)
        assertEquals(0.95f, b.defaultConfig.topP, 0.0001f)
        assertEquals(40, b.defaultConfig.topK)
        assertEquals(1, b.socToModelFiles.size)
        assertEquals("deadbeef", b.socToModelFiles.values.first().commitHash)
    }

    @Test
    fun `visibleEntries filters by schema version and minAppVersion`() {
        val catalog = ModelCatalogParser.parse(catalogJson)
        val visible = ModelCatalogParser.visibleEntries(catalog, currentAppVersion = "2.0.0")
        assertEquals(listOf("model-a"), visible.map { it.id })

        // Entry with minAppVersion above current app version is hidden
        val visibleOld = ModelCatalogParser.visibleEntries(catalog, currentAppVersion = "0.5.0")
        assertTrue(visibleOld.isEmpty())
    }

    @Test
    fun `unsupported schema version yields no visible entries`() {
        val unsupported = """{"schemaVersion": 2, "models": [{"id": "x"}]}"""
        val catalog = ModelCatalogParser.parse(unsupported)
        assertTrue(ModelCatalogParser.visibleEntries(catalog, "1.0.0").isEmpty())
    }

    @Test
    fun `entries with blank id are never visible`() {
        val catalog = ModelCatalogParser.parse(
            """{"schemaVersion": 1, "models": [{"id": "", "minAppVersion": "0.0.0"}]}"""
        )
        assertTrue(ModelCatalogParser.visibleEntries(catalog, "1.0.0").isEmpty())
    }

    @Test
    fun `unknown keys are ignored`() {
        val catalog = ModelCatalogParser.parse(
            """{"schemaVersion": 1, "models": [{"id": "m", "futureField": 42}]}"""
        )
        assertEquals(listOf("m"), ModelCatalogParser.visibleEntries(catalog, "1.0.0").map { it.id })
    }

    @Test
    fun `version comparison handles differing lengths and non-numeric suffixes`() {
        assertEquals(-1, ModelCatalogParser.compareAppVersions("1.2", "1.2.1"))
        assertEquals(1, ModelCatalogParser.compareAppVersions("1.10.0", "1.9.9"))
        assertEquals(0, ModelCatalogParser.compareAppVersions("2.0.0-beta", "2.0.0"))
    }

    @Test
    fun `formatDownloadSize uses human units`() {
        assertEquals("500 KB", ModelCatalogParser.formatDownloadSize(512_000))
        assertEquals("50 MB", ModelCatalogParser.formatDownloadSize(52_428_800))
        assertEquals("1.0 GB", ModelCatalogParser.formatDownloadSize(1_073_741_824))
        assertEquals("10 GB", ModelCatalogParser.formatDownloadSize(10_737_418_240))
    }
}
