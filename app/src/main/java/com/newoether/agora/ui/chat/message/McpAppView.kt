package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.mcp.McpRegistry
import com.newoether.agora.mcp.McpUiResource
import com.newoether.agora.mcp.appVersionName
import com.newoether.agora.mcp.ui.McpAppBridgeRouter
import com.newoether.agora.mcp.ui.McpAppHostPort
import com.newoether.agora.mcp.ui.McpAppToolOutcome
import com.newoether.agora.mcp.ui.McpAppWebSession
import com.newoether.agora.mcp.ui.McpAppWebSupport
import com.newoether.agora.mcp.ui.buildCallToolResult
import com.newoether.agora.mcp.ui.buildMcpAppHostContext
import com.newoether.agora.mcp.ui.buildMcpAppToolInput
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolExecutionStates
import com.newoether.agora.ui.theme.ChatType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.TimeZone

private const val MCP_APP_DEFAULT_HEIGHT_DP = 320
private const val MCP_APP_MIN_HEIGHT_DP = 120
private const val MCP_APP_MAX_HEIGHT_DP = 800

private sealed interface McpAppLoadState {
    data object Loading : McpAppLoadState
    data object Failed : McpAppLoadState
    data class Ready(val resource: McpUiResource, val registry: McpRegistry) : McpAppLoadState
}

private class PendingMcpAppLink(
    val url: String,
    val result: CompletableDeferred<Boolean>,
)

/**
 * Entry point shown in an MCP tool's detail when its persisted result carries a `ui://` pointer.
 * Nothing is fetched and no WebView exists until the user opens the view; closing it (or leaving
 * the composition) destroys the WebView. The plain result below stays the source of truth.
 */
@Composable
internal fun McpAppEntry(segment: MessageSegment, modifier: Modifier = Modifier) {
    val serverId = segment.toolUiServerId ?: return
    val resourceUri = segment.toolUiResourceUri ?: return
    if (segment.toolState != ToolExecutionStates.SUCCEEDED) return
    if (!remember { McpAppWebSupport.isSupported() }) return
    var open by rememberSaveable(segment.toolCallId, resourceUri) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        if (open) {
            McpAppHost(segment = segment, serverId = serverId, resourceUri = resourceUri)
        }
        TextButton(onClick = { open = !open }) {
            Text(
                stringResource(
                    if (open) R.string.mcp_app_close_view else R.string.mcp_app_open_view,
                ),
            )
        }
    }
}

@Composable
private fun McpAppHost(segment: MessageSegment, serverId: String, resourceUri: String) {
    val context = LocalContext.current
    val registry = remember(context) {
        (context.applicationContext as? AgoraApplication)?.containerIfAvailable()?.mcpRegistry
    }
    var loadState by remember(serverId, resourceUri) {
        mutableStateOf<McpAppLoadState>(McpAppLoadState.Loading)
    }
    LaunchedEffect(registry, serverId, resourceUri) {
        loadState = if (registry == null) {
            McpAppLoadState.Failed
        } else {
            try {
                McpAppLoadState.Ready(registry.readUiResource(serverId, resourceUri), registry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                McpAppLoadState.Failed
            }
        }
    }
    when (val state = loadState) {
        McpAppLoadState.Loading -> McpAppStatusText(stringResource(R.string.mcp_app_loading))
        McpAppLoadState.Failed -> McpAppStatusText(stringResource(R.string.mcp_app_unavailable))
        is McpAppLoadState.Ready -> McpAppWebContent(
            segment = segment,
            serverId = serverId,
            resource = state.resource,
            registry = state.registry,
        )
    }
}

@Composable
private fun McpAppStatusText(text: String) {
    Text(
        text = text,
        style = ChatType.metaNormal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun McpAppWebContent(
    segment: MessageSegment,
    serverId: String,
    resource: McpUiResource,
    registry: McpRegistry,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var heightDp by remember { mutableStateOf(MCP_APP_DEFAULT_HEIGHT_DP) }
    var crashed by remember(resource) { mutableStateOf(false) }
    var pendingLink by remember(resource) { mutableStateOf<PendingMcpAppLink?>(null) }

    val session = remember(resource, serverId) {
        val port = object : McpAppHostPort {
            override suspend fun callTool(name: String, arguments: JsonObject): McpAppToolOutcome {
                val result = registry.callToolForApp(serverId, name, arguments)
                return McpAppToolOutcome(
                    text = result.displayText ?: result.text,
                    structuredContent = result.structuredContent,
                    isError = result.isError,
                )
            }

            override suspend fun openLink(url: String): Boolean {
                pendingLink?.result?.complete(false)
                val decision = CompletableDeferred<Boolean>()
                pendingLink = PendingMcpAppLink(url, decision)
                return decision.await()
            }

            override fun onSizeChanged(heightCssPx: Int) {
                // CSS pixels are density-independent pixels in the WebView.
                heightDp = heightCssPx.coerceIn(MCP_APP_MIN_HEIGHT_DP, MCP_APP_MAX_HEIGHT_DP)
            }

            override fun onLog(message: String) = Unit
        }
        val router = McpAppBridgeRouter(
            port = port,
            hostContext = buildMcpAppHostContext(
                isDark = isDark,
                localeTag = Locale.getDefault().toLanguageTag(),
                timeZoneId = TimeZone.getDefault().id,
                maxHeightCssPx = MCP_APP_MAX_HEIGHT_DP,
            ),
            hostVersion = appVersionName(context),
            toolInput = buildMcpAppToolInput(segment.toolArgs),
            toolResult = buildCallToolResult(
                text = segment.toolResultText ?: segment.toolResult.orEmpty(),
                structuredContent = segment.toolStructuredResult,
                isError = false,
            ),
        )
        McpAppWebSession(
            context = context,
            serverId = serverId,
            resource = resource,
            router = router,
            scope = scope,
            onRenderProcessGone = { crashed = true },
        )
    }

    DisposableEffect(session) {
        session.start()
        onDispose {
            pendingLink?.result?.complete(false)
            session.close()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (crashed) {
            McpAppStatusText(stringResource(R.string.mcp_app_unavailable))
        } else {
            AndroidView(
                factory = { session.webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = {
                link.result.complete(false)
                pendingLink = null
            },
            title = { Text(stringResource(R.string.mcp_app_open_link_title)) },
            text = { Text(link.url) },
            confirmButton = {
                TextButton(
                    onClick = {
                        link.result.complete(true)
                        pendingLink = null
                        runCatching { uriHandler.openUri(link.url) }
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        link.result.complete(false)
                        pendingLink = null
                    },
                ) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
