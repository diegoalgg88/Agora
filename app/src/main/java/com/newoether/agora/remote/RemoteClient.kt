package com.newoether.agora.remote

import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolImageAttachment
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * El seam que consumen RemoteViewModel/RemoteDeviceDirectory: hoy lo implementa
 * FiloClient (transporte Conch) y QwenCodeClient (ACP de `qwen serve`). Una
 * conexión guardada elige la implementación por [RemoteConnection.kind].
 */
internal interface RemoteClient {
    val address: String

    suspend fun connect(): String
    suspend fun sessions(cursor: String? = null): RemoteSessionPage
    suspend fun conversation(id: String, cursor: String? = null): RemoteConversationPage
    suspend fun image(
        id: String, requested: RemotePayloadRequest,
        persist: (InputStream, String) -> ToolImageAttachment,
    ): ToolImageAttachment
    fun events(id: String): Flow<RemoteConversationPage>
    suspend fun rename(id: String, name: String): RemoteSession
    suspend fun archiveSession(id: String)
    suspend fun create(
        text: String, clientId: String,
        attachments: List<String> = emptyList(), settings: RemoteSettings? = null,
    ): RemoteSession
    suspend fun models(): List<RemoteModel>
    suspend fun usage(): RemoteUsage
    suspend fun setModel(id: String, model: String)
    suspend fun updateSettings(id: String, settings: RemoteSettings)
    suspend fun stop(id: String, turnId: String)
    suspend fun upload(item: SelectedAttachment): RemoteUpload
    suspend fun send(id: String, text: String, clientId: String, attachments: List<String> = emptyList()): String

    /**
     * Permission requests vivas (solo clientes ACP; Filo no tiene el concepto): la UI
     * de RemoteConversation presenta las de la sesión abierta y responde por id.
     */
    val pendingPermissionRequests: kotlinx.coroutines.flow.StateFlow<List<RemotePermissionRequest>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    /** Respuesta a un permission request: optionId seleccionado, o null = cancelled. */
    suspend fun respondToPermission(requestId: String, optionId: String?) {}

    /** El cliente ACP lanza collectors propios; cerrarlos aquí es no-op para Filo. */
    fun close() {}
}
