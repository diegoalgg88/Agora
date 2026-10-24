package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.automation.SubagentManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Delegation is restricted to a currently active root conversation's exact tool identity. */
class SubagentToolProvider(private val manager: SubagentManager) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.subagentToolsEnabled) return emptyList()
        return listOf(
            definition("spawn_agent", "Start an isolated subagent asynchronously. It receives only task, never this conversation's history. Enabled model IDs: ${Json.encodeToString(ctx.enabledModelIds)}", mapOf(
                "name" to ToolProperty("string", "Short subagent name."),
                "model_id" to ToolProperty("string", "One exact enabled model ID."),
                "task" to ToolProperty("string", "Complete task and all necessary context."),
            ), listOf("name", "model_id", "task")),
            definition("send_agent_message", "Send a new task to an idle subagent belonging to this conversation.", mapOf(
                "agent_id" to ToolProperty("string", "Subagent conversation ID."),
                "message" to ToolProperty("string", "Complete follow-up instruction."),
            ), listOf("agent_id", "message")),
            definition("wait_agents", "Wait for selected subagents and return their undelivered terminal results. Returned results are not automatically notified again.", mapOf(
                "agent_ids" to ToolProperty("array", "Subagent IDs; omit to wait for all.", items = ToolProperty("string", "Subagent ID")),
                "timeout_ms" to ToolProperty("integer", "Wait budget, 1 to 300000 milliseconds; default 300000."),
            )),
            definition("list_agents", "List this conversation's subagents and their current status.", emptyMap()),
            definition("stop_agent", "Cancel a subagent's live generation and await durable Stop settlement.", mapOf(
                "agent_id" to ToolProperty("string", "Subagent conversation ID."),
            ), listOf("agent_id")),
        )
    }

    override fun handles(name: String) = name in NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text

    override suspend fun executeOneShotResult(name: String, arguments: String, ctx: GenerationContext): ToolExecutionResult = try {
        check(ctx.subagentToolsEnabled && name in NAMES) { "Subagent tools are unavailable" }
        val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        fun string(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf(String::isNotBlank) ?: error("$key is required")
        val result = when (name) {
            "spawn_agent" -> buildJsonObject {
                put("agent_id", manager.spawn(string("name"), string("model_id"), string("task"), ctx))
                put("status", "started")
            }.toString()
            "send_agent_message" -> {
                manager.send(string("agent_id"), string("message"), ctx)
                "Message sent"
            }
            "wait_agents" -> {
                val ids = (args["agent_ids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                val timeout = (args["timeout_ms"] as? JsonPrimitive)?.longOrNull ?: 300_000L
                val results = manager.wait(ids, timeout, ctx)
                buildJsonObject {
                    putJsonObject("subagent_receipts") {
                        results.forEach { (id, result) -> put(id, result.id) }
                    }
                    putJsonArray("results") {
                        results.forEach { (id, result) -> add(buildJsonObject {
                            put("agent_id", id)
                            put("status", result.status)
                            put("text", result.text)
                        }) }
                    }
                }.toString()
            }
            "list_agents" -> Json.encodeToString(manager.list(ctx))
            "stop_agent" -> {
                manager.stop(string("agent_id"), ctx)
                "Subagent stopped"
            }
            else -> error("Unknown subagent tool")
        }
        ToolExecutionResult(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolExecutionResult("Error: ${error.message ?: "Subagent operation failed"}", isError = true)
    }

    private fun definition(
        name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList(),
    ) = ToolDefinition(function = ToolFunction(name = name, description = description,
        parameters = ToolParameters(properties = properties, required = required)))

    private companion object {
        val NAMES = setOf("spawn_agent", "send_agent_message", "wait_agents", "list_agents", "stop_agent")
    }
}
