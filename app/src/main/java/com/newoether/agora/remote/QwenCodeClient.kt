package com.newoether.agora.remote

import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Adaptador ACP→shapes-Filo: expone el mismo seam que [FiloClient] para que
 * RemoteViewModel/RemoteDeviceDirectory lo consuman sin cambios, hablando
 * JSON-RPC contra el endpoint /acp de `qwen serve`.
 *
 * Cobertura v1 (code-verified contra dispatch.ts):
 * - connect() → initialize (guarda acp-connection-id; el collector del SSE
 *   connection-scoped resuelve las respuestas de los requests).
 * - sessions() → session/list {cursor, archiveState:"active"}.
 * - create() → session/new + session/prompt con el primer mensaje.
 * - send() → session/prompt; el turnId es local (cancel no lo necesita).
 * - stop() → session/cancel {sessionId}.
 * - setModel() → session/set_model {sessionId, modelId}.
 * - updateSettings() → session/set_config_option {sessionId, configId, value}.
 * - events() → session/update traducido a RemoteConversationPage acumulativo.
 * - respondToPermission() → session/permission {requestId, outcome}.
 *
 * Degradaciones documentadas (sin equivalente ACP directo): usage() y
 * image()/upload() fallan con error explícito; rename()/archiveSession()
 * son no-ops; models() llega vía configOptions del session/new cuando el
 * server las envía.
 */
internal class QwenCodeClient(
    address: String,
    token: String,
) : RemoteClient {
    private val transport = QwenCodeTransport(address, token)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deviceName: String = "Qwen Code"
    private var initialized = false

    /** Mensajes acumulados por sesión: cada session/update extiende la página. */
    private val sessionMessages = HashMap<String, MutableList<RemoteMessage>>()
    private val sessionRuntime = HashMap<String, RemoteRuntime>()
    /** Permission requests vivas: requestId → (sessionId, frame crudo). */
    private val pendingPermissions = HashMap<String, Pair<String, JsonObject>>()

    override val address: String get() = transport.acpAddress

    /** Collector del SSE conn-scoped: se reinicia en cada connect (el connId cambia). */
    private var collector: kotlinx.coroutines.Job? = null

    override suspend fun connect(): String {
        val capabilities = transport.initialize()
        // El collector del SSE conn-scoped DEBE vivir antes del primer request: las
        // respuestas llegan por ese stream, demultiplexadas por id. Se (re)inicia en
        // CADA connect — el transport cancela el stream viejo en re-initialize y sus
        // respuestas pendientes ya fallaron; sin collector nuevo, los requests del
        // connId vigente no tendrían demuxer. El loop tolera la muerte del stream
        // (timeout/EOF del server) y reintenta tras pausa: un fallo de transporte jamás
        // escapa del scope — el ciclo de polling del ViewModel ya es el mecanismo
        // ordinario de reporte y recovery.
        collector?.cancel()
        collector = scope.launch {
            while (isActive) {
                try {
                    transport.connectionEvents().collect { frame ->
                        frame["method"]?.jsonPrimitive?.contentOrNull?.let { method ->
                            if (method == "permission_request") onPermissionRequest(frame)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* stream muerto; re-establecer tras pausa */ }
                kotlinx.coroutines.delay(15_000)
            }
        }
        capabilities["agentInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?.let { deviceName = it }
        return deviceName
    }

    private fun onPermissionRequest(frame: JsonObject) {
        val params = frame["params"]?.jsonObject ?: return
        val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        pendingPermissions[requestId] = sessionId to params
    }

    /** Aprobación de tool calls: outcome "selected" con optionId, o "cancelled". */
    suspend fun respondToPermission(requestId: String, optionId: String?) {
        transport.request("session/permission", buildJsonObject {
            put("requestId", requestId)
            put("outcome", buildJsonObject {
                if (optionId == null) put("outcome", "cancelled")
                else { put("outcome", "selected"); put("optionId", optionId) }
            })
        })
        pendingPermissions.remove(requestId)
    }

    override suspend fun sessions(cursor: String?): RemoteSessionPage {
        val result = transport.request("session/list", buildJsonObject {
            cursor?.let { put("cursor", it) }
            put("archiveState", "active")
        })
        val sessions = result["sessions"]?.jsonObject?.let { org ->
            org.keys.mapNotNull { key -> org[key] as? JsonObject }
        } ?: run {
            // El dispatcher manda sessions como array JSON.
            result["sessions"]?.let { element ->
                (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
            } ?: emptyList()
        }
        return RemoteSessionPage(
            sessions = sessions.mapNotNull { s ->
                val id = s["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RemoteSession(
                    id = id,
                    title = s["title"]?.jsonPrimitive?.contentOrNull
                        ?: s["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    cwd = s["cwd"]?.jsonPrimitive?.contentOrNull
                        ?: s["workspaceCwd"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    updatedAt = s["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                    status = if (s["hasActivePrompt"]?.jsonPrimitive?.contentOrNull == "true") "active" else null,
                    listCursor = cursor,
                )
            },
            nextCursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun conversation(id: String, cursor: String?): RemoteConversationPage {
        // La paginación de historial vía ACP no existe como tal: el ring replay
        // del SSE entrega lo reciente; degradamos la carga de páginas viejas.
        return RemoteConversationPage(
            messages = sessionMessages[id].orEmpty().toList(),
            nextCursor = null,
            queued = emptyList(),
            runtime = sessionRuntime[id] ?: RemoteRuntime(status = "idle"),
        )
    }

    override suspend fun create(
        text: String, clientId: String,
        attachments: List<String>, settings: RemoteSettings?,
    ): RemoteSession {
        require(text.isNotBlank() || attachments.isNotEmpty()) { "Empty prompt" }
        val newResult = transport.request("session/new", buildJsonObject {
            settings?.model?.let { put("startupConfig", buildJsonObject { put("model", it) }) }
        })
        val sessionId = newResult["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: throw QwenCodeProtocolException(message = "session/new without sessionId")
        settings?.model?.let { model ->
            transport.request("session/set_model", buildJsonObject {
                put("sessionId", sessionId); put("modelId", model)
            })
        }
        settings?.effort?.takeIf(String::isNotBlank)?.let { effort ->
            transport.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId); put("configId", "effort"); put("value", effort)
            })
        }
        val turnId = prompt(sessionId, text, clientId, attachments)
        sessionMessages.getOrPut(sessionId) { mutableListOf() }.add(RemoteMessage(
            id = "local-$turnId", turnId = turnId, clientId = clientId, role = "user",
            text = text, timestamp = System.currentTimeMillis(),
        ))
        return RemoteSession(id = sessionId, title = "", cwd = "", updatedAt = System.currentTimeMillis())
    }

    override suspend fun send(id: String, text: String, clientId: String, attachments: List<String>): String {
        val turnId = prompt(id, text, clientId, attachments)
        sessionMessages.getOrPut(id) { mutableListOf() }.add(RemoteMessage(
            id = "local-$turnId", turnId = turnId, clientId = clientId, role = "user",
            text = text, timestamp = System.currentTimeMillis(),
        ))
        return turnId
    }

    private suspend fun prompt(sessionId: String, text: String, clientId: String, attachments: List<String>): String {
        if (text.isBlank() && attachments.isEmpty()) throw QwenCodeConfigurationException()
        val result = transport.request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            })
            put("_meta", buildJsonObject {
                put("qwen", buildJsonObject { put("clientId", clientId) })
            })
        })
        return result["turnId"]?.jsonPrimitive?.contentOrNull ?: "turn-${System.nanoTime()}"
    }

    override suspend fun stop(id: String, turnId: String) {
        // ACP cancela la sesión completa; el turnId local es solo informativo.
        transport.request("session/cancel", buildJsonObject { put("sessionId", id) })
    }

    override suspend fun setModel(id: String, model: String) {
        transport.request("session/set_model", buildJsonObject {
            put("sessionId", id); put("modelId", model)
        })
    }

    override suspend fun updateSettings(id: String, settings: RemoteSettings) {
        settings.model?.let { model -> setModel(id, model) }
        settings.effort?.takeIf(String::isNotBlank)?.let { effort ->
            transport.request("session/set_config_option", buildJsonObject {
                put("sessionId", id); put("configId", "effort"); put("value", effort)
            })
        }
    }

    /**
     * Stream de la sesión: cada session/update extiende los mensajes acumulados
     * y emite la página completa — el mismo contrato incremental que Filo.
     */
    override fun events(id: String): Flow<RemoteConversationPage> =
        transport.sessionEvents(id).map { (frame, cursor) ->
            val params = frame["params"]?.jsonObject ?: return@map page(id, cursor)
            val update = params["update"]?.jsonObject ?: return@map page(id, cursor)
            when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
                "user_message" -> appendUser(id, update)
                "agent_message" -> appendAgent(id, update, cursor)
                "agent_thought" -> Unit
                "tool_call" -> appendToolCall(id, update)
                "plan" -> Unit
            }
            page(id, cursor)
        }

    private fun textOf(update: JsonObject): String {
        val content = update["content"] as? kotlinx.serialization.json.JsonArray ?: return ""
        return content.filterIsInstance<JsonObject>().mapNotNull { block ->
            if (block["type"]?.jsonPrimitive?.contentOrNull == "text")
                block["text"]?.jsonPrimitive?.contentOrNull else null
        }.joinToString("\n")
    }

    private fun appendUser(id: String, update: JsonObject) {
        // El user_message del eco del server reemplaza el local del mismo turn.
        val messages = sessionMessages.getOrPut(id) { mutableListOf() }
        val text = textOf(update)
        val stamp = System.currentTimeMillis()
        messages.removeAll { it.role == "user" && it.text == text && it.id.startsWith("local-") }
        messages.add(RemoteMessage(
            id = update["messageId"]?.jsonPrimitive?.contentOrNull ?: "user-${stamp}",
            turnId = update["turnId"]?.jsonPrimitive?.contentOrNull ?: "turn",
            clientId = null, role = "user", text = text, timestamp = stamp,
        ))
    }

    private fun appendAgent(id: String, update: JsonObject, cursor: String?) {
        val messages = sessionMessages.getOrPut(id) { mutableListOf() }
        val text = textOf(update)
        val nativeId = update["messageId"]?.jsonPrimitive?.contentOrNull
        // Streaming del mismo mensaje: reemplaza la entrada previa por nativeId.
        val existing = messages.indexOfFirst { it.nativeId != null && it.nativeId == nativeId }
        val message = RemoteMessage(
            id = nativeId ?: "agent-${cursor ?: System.nanoTime()}", turnId = update["turnId"]
                ?.jsonPrimitive?.contentOrNull ?: "turn", clientId = null, role = "assistant",
            text = text, timestamp = System.currentTimeMillis(), nativeId = nativeId,
        )
        if (existing >= 0) messages[existing] = message else messages.add(message)
        sessionRuntime[id] = RemoteRuntime(status = "active")
    }

    private fun appendToolCall(id: String, update: JsonObject) {
        val messages = sessionMessages.getOrPut(id) { mutableListOf() }
        val toolName = update["toolCall"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
        val content = update["content"] as? kotlinx.serialization.json.JsonArray
        val state = update["toolCall"]?.jsonObject?.get("state")?.jsonObject
            ?.get("status")?.jsonPrimitive?.contentOrNull
        val existing = messages.indexOfFirst { it.activity?.type == "tool" &&
            it.activity.toolName == toolName && it.nativeId == "tool-$toolName-${content?.size}" }
        val activity = RemoteActivity(type = "tool", toolName = toolName, state = state,
            arguments = content?.toString())
        if (existing >= 0) {
            messages[existing] = messages[existing].copy(activity = activity)
        } else {
            messages.add(RemoteMessage(
                id = "tool-${System.nanoTime()}", turnId = "turn", clientId = null,
                role = "assistant", text = "", timestamp = System.currentTimeMillis(),
                activity = activity, nativeId = "tool-$toolName-${content?.size}",
            ))
        }
    }

    private fun page(id: String, cursor: String?): RemoteConversationPage = RemoteConversationPage(
        messages = sessionMessages[id].orEmpty().toList(),
        nextCursor = null, queued = emptyList(),
        runtime = sessionRuntime[id] ?: RemoteRuntime(status = "idle"),
        continuationCursor = cursor,
    )

    // ── Degradaciones documentadas: sin equivalente ACP directo ──

    /** Rename via metadata es _qwen/vendor; v1 no-op hasta verificar el shape. */
    override suspend fun rename(id: String, name: String): RemoteSession {
        transport.request("_qwen/session/update_metadata", buildJsonObject {
            put("sessionId", id); put("displayName", name)
        })
        return RemoteSession(id = id, title = name, cwd = "", updatedAt = System.currentTimeMillis())
    }

    override suspend fun archiveSession(id: String) {
        // Sin método ACP de archivo: el listado filtra archiveState="active"
        // y el server decide; v1 lo declina explícito.
        throw QwenCodeProtocolException(message = "Archive is not available on Qwen Code")
    }

    override suspend fun models(): List<RemoteModel> = emptyList()

    override suspend fun usage(): RemoteUsage = throw QwenCodeProtocolException(message = "Usage is not available on Qwen Code")

    override suspend fun upload(item: SelectedAttachment): RemoteUpload =
        throw RemoteAttachmentException("Attachments are not supported on Qwen Code yet")

    override suspend fun image(
        id: String, requested: RemotePayloadRequest,
        persist: (java.io.InputStream, String) -> ToolImageAttachment,
    ): ToolImageAttachment = throw RemoteAttachmentException("Remote images are not supported on Qwen Code yet")

    override fun close() {
        scope.cancel()
    }
}
