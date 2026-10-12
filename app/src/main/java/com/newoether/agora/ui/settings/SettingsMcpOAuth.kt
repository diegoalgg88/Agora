package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.mcp.McpServerSnapshot
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * OAuth section of the MCP server editor: optional pre-registered client id and scope (stored in
 * [McpServerConfig]), plus sign-in / sign-out. Sign-in always acts on the *saved* server, because
 * the flow is driven by the stored URL and client settings.
 */
@Composable
internal fun McpOAuthGroup(
    viewModel: ChatViewModel,
    draft: McpServerConfig,
    saved: McpServerConfig,
    isNew: Boolean,
    snapshot: McpServerSnapshot?,
    onDraftChange: (McpServerConfig) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(saved.id) { mutableStateOf(false) }
    var failure by remember(saved.id) { mutableStateOf<String?>(null) }
    val signedIn by produceState(
        initialValue = false,
        saved.id,
        snapshot?.status,
        snapshot?.lastSyncedAt,
        snapshot?.authRequired,
    ) { value = viewModel.mcpOAuth.isSignedIn(saved.id) }
    val unsaved = isNew ||
        draft.oauthClientId != saved.oauthClientId ||
        draft.oauthScope != saved.oauthScope

    SettingsGroup(
        title = stringResource(R.string.mcp_oauth_title),
        items = buildList {
            add {
                SettingsIconContent(icon = Icons.Default.Key) {
                    McpLabeledField(
                        label = stringResource(R.string.mcp_oauth_client_id),
                        value = draft.oauthClientId,
                        onValueChange = { onDraftChange(draft.copy(oauthClientId = it)) },
                        supportingText = stringResource(R.string.mcp_oauth_client_id_desc),
                    )
                }
            }
            add {
                SettingsIconContent(icon = Icons.Default.Tune) {
                    McpLabeledField(
                        label = stringResource(R.string.mcp_oauth_scope),
                        value = draft.oauthScope,
                        onValueChange = { onDraftChange(draft.copy(oauthScope = it)) },
                        supportingText = stringResource(R.string.mcp_oauth_scope_desc),
                    )
                }
            }
            add {
                SettingsItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                when {
                                    signedIn -> R.string.mcp_oauth_signed_in
                                    snapshot?.authRequired == true -> R.string.mcp_oauth_required
                                    else -> R.string.mcp_oauth_signed_out
                                },
                            ),
                            color = if (signedIn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    supportingContent = {
                        val message = failure ?: if (unsaved) {
                            stringResource(R.string.mcp_oauth_save_first)
                        } else {
                            null
                        }
                        if (message != null) {
                            Text(
                                message,
                                color = if (failure != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        } else {
                            Spacer(Modifier.height(0.dp))
                        }
                    },
                    leadingContent = {
                        Icon(
                            if (signedIn) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        if (signedIn) {
                            TextButton(
                                enabled = !busy && !isNew,
                                onClick = {
                                    scope.launch {
                                        failure = null
                                        runCatching { viewModel.mcpOAuth.signOut(saved.id) }
                                    }
                                },
                            ) { Text(stringResource(R.string.mcp_oauth_sign_out)) }
                        } else {
                            TextButton(
                                enabled = !busy && !unsaved,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        failure = null
                                        try {
                                            val url = viewModel.mcpOAuth.authorizationUrl(saved.id)
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            failure = e.message ?: e.javaClass.simpleName
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.mcp_oauth_sign_in)) }
                        }
                    },
                )
            }
        },
    )
}
