package com.newoether.agora.mcp

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.util.validateToolDefinitions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los schemas de tools MCP llegan a los providers intactos, incluido anidamiento que el modelo tipado no puede expresar. */
class McpToolSchemaWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // Formas tomadas de los tools de Playwright MCP que fallaron en la conversación reportada.
    private val dropSchema = json.parseToJsonElement(
        """
        {"${'$'}schema":"http://json-schema.org/draft-07/schema#","type":"object",
         "properties":{
           "target":{"type":"string"},
           "data":{"type":"object","additionalProperties":{"type":"string"},
                   "description":"MIME type to value"}},
         "required":["target","missing"],"additionalProperties":false}
        """,
    ).jsonObject
    private val fillFormSchema = json.parseToJsonElement(
        """
        {"type":"object","properties":{"fields":{"type":"array","items":{"type":"object",
          "properties":{"name":{"type":"string"},"type":{"type":"string","enum":["textbox","checkbox"]}},
          "required":["name","type"]}}},"required":["fields"]}
        """,
    ).jsonObject

    private fun definition(name: String, schema: JsonObject): ToolDefinition = McpToolDescriptor(
        publicName = name,
        serverId = "server",
        serverName = "Browser",
        remote = McpRemoteTool(name = name, description = "d", inputSchema = schema),
    ).asToolDefinition()

    @Test
    fun wireSchemaKeepsNestingAndNormalizesOnlyTheTopLevel() {
        val schema = definition("mcp_drop", dropSchema).function.parameters.schema!!
        assertFalse(schema.containsKey("\$schema"))
        assertEquals("[\"target\"]", schema["required"].toString())
        assertEquals("false", schema["additionalProperties"].toString())
        assertEquals(
            dropSchema["properties"]!!.jsonObject["data"],
            schema["properties"]!!.jsonObject["data"],
        )
    }

    @Test
    fun openAiWireFormatSendsTheServerSchema() {
        val tool = definition("mcp_fill_form", fillFormSchema)
        val parameters = json.parseToJsonElement(json.encodeToString(ToolDefinition.serializer(), tool))
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(fillFormSchema, parameters)
    }

    @Test
    fun mapAndNestedObjectSchemasPassLocalValidation() {
        val violations = validateToolDefinitions(
            listOf(definition("mcp_drop", dropSchema), definition("mcp_fill_form", fillFormSchema)),
        )
        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test
    fun typedSchemasAreStillCheckedAndWrittenAsBefore() {
        val typed = ToolDefinition(
            function = ToolFunction(
                name = "built_in",
                description = "d",
                parameters = ToolParameters(
                    properties = mapOf("opts" to ToolProperty(type = "array", description = "")),
                ),
            ),
        )
        assertEquals(
            listOf("tool built_in array opts has no items schema"),
            validateToolDefinitions(listOf(typed)),
        )
        val parameters = json.parseToJsonElement(json.encodeToString(ToolDefinition.serializer(), typed))
            .jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(
            "{\"type\":\"object\",\"properties\":{\"opts\":{\"type\":\"array\",\"description\":\"\"}},\"required\":[]}",
            parameters.toString(),
        )
    }
}
