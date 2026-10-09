package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.mcp.McpRemoteResource

/** Resources shown per server; the registry keeps up to 500 but a long list would compose all at once. */
private const val MCP_RESOURCES_DISPLAY_LIMIT = 50

/**
 * Display-only list of the resources a server advertises (`resources/list`). Nothing here is sent to
 * a model or read automatically; see `development/mcp.md` §9.2b.
 */
@Composable
internal fun McpResourcesGroup(resources: List<McpRemoteResource>) {
    val shown = resources.take(MCP_RESOURCES_DISPLAY_LIMIT)
    SettingsGroup(
        title = stringResource(R.string.mcp_resources_count, resources.size),
        items = buildList {
            shown.forEach { resource ->
                add {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                text = resource.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = resource.uri,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                resource.description?.let { description ->
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (resource.isMcpAppDocument) {
                                    Icons.Default.Widgets
                                } else {
                                    Icons.Default.Description
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            if (resources.size > shown.size) {
                add {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                text = stringResource(
                                    R.string.mcp_resources_more,
                                    resources.size - shown.size,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        },
    )
}

internal fun buildMcpHeaders(headers: List<McpHeaderDraft>): Map<String, String> {
    return buildMap {
        headers
            .filterNot { it.name.isBlank() && it.value.isBlank() }
            .forEach { header ->
                put(header.name.trim(), header.value.trim())
            }
    }
}

internal fun isValidMcpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        uri.host != null &&
        uri.userInfo == null &&
        uri.fragment == null
}
