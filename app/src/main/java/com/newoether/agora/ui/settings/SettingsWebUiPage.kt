package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.AgoraApplication
import com.newoether.agora.R
import com.newoether.agora.webui.WebUiController
import com.newoether.agora.webui.WebUiSettingsStore
import com.newoether.agora.webui.WebUiStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings > WebUI: enciende el control remoto por navegador, elige puerto y contraseña. */
@Composable
fun SettingsWebUiPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val webUi = remember { (context.applicationContext as AgoraApplication).requireContainer().webUi }
    val enabled by webUi.enabled.collectAsState(initial = false)
    val port by webUi.port.collectAsState(initial = WebUiSettingsStore.DEFAULT_PORT)
    val hasPassword by webUi.hasPassword.collectAsState(initial = false)
    val status by webUi.status.collectAsState()
    val scope = rememberCoroutineScope()
    var passwordDialog by remember { mutableStateOf(false) }

    CollapsingSettingsScaffold(title = stringResource(R.string.settings_webui), onBack = onBack) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.settings_webui),
                items = listOf(
                    {
                        val toggle = { scope.launch { webUi.setEnabled(!enabled) }; Unit }
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.webui_enable)) },
                            supportingContent = {
                                Text(
                                    if (!hasPassword) {
                                        stringResource(R.string.webui_password_required)
                                    } else {
                                        statusText(status, enabled)
                                    },
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Web, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    enabled = hasPassword || enabled,
                                    onCheckedChange = { toggle() },
                                )
                            },
                            modifier = Modifier.clickable(enabled = hasPassword || enabled) { toggle() },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        if (hasPassword) R.string.webui_password_change else R.string.webui_password_set,
                                    ),
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (hasPassword) R.string.webui_password_desc_set else R.string.webui_password_desc_unset,
                                    ),
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.clickable { passwordDialog = true },
                        )
                    },
                    { WebUiPortField(port = port, onPortChange = { scope.launch { webUi.setPort(it) } }) },
                ),
            )
            SettingsGroup(
                title = stringResource(R.string.webui_access),
                items = listOf(
                    {
                        SettingsIconContent(icon = Icons.Default.Link) {
                            val running = status as? WebUiStatus.Running
                            val urls = running?.let { webUi.accessUrls(it.port) }.orEmpty()
                            Text(
                                stringResource(
                                    if (running != null && urls.isEmpty()) R.string.webui_access_none else R.string.webui_access_urls_desc,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (urls.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Column { urls.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                                }
                            }
                        }
                    },
                    {
                        SettingsIconContent(icon = Icons.Default.Warning) {
                            Text(
                                stringResource(R.string.webui_http_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ),
            )
        }
    }

    if (passwordDialog) {
        WebUiPasswordDialog(
            webUi = webUi,
            isChange = hasPassword,
            onDismiss = { passwordDialog = false },
        )
    }
}

@Composable
private fun statusText(status: WebUiStatus, enabled: Boolean): String = when {
    status is WebUiStatus.Running -> stringResource(R.string.webui_status_running, status.port)
    status is WebUiStatus.Failed -> stringResource(R.string.webui_status_failed, status.message)
    enabled -> stringResource(R.string.webui_status_starting)
    else -> stringResource(R.string.webui_status_stopped)
}

/** Guarda un puerto válido cuando el teclear se detiene; cada tecla no reinicia el servidor. */
@Composable
private fun WebUiPortField(port: Int, onPortChange: (Int) -> Unit) {
    var draft by remember(port) { mutableStateOf(port.toString()) }
    val parsed = draft.toIntOrNull()
    val valid = parsed != null && parsed in WebUiSettingsStore.PORT_RANGE
    LaunchedEffect(draft) {
        delay(PORT_COMMIT_DELAY_MILLIS)
        if (valid && parsed != port) onPortChange(parsed!!)
    }
    SettingsIconContent(icon = Icons.Default.Numbers) {
        Text(
            stringResource(R.string.webui_port),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.webui_port_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit).take(5) },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun WebUiPasswordDialog(webUi: WebUiController, isChange: Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tooShort = password.length < WebUiController.MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(
                stringResource(if (isChange) R.string.webui_password_change else R.string.webui_password_set),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.webui_password_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (password.isNotEmpty() && tooShort) {
                        { Text(stringResource(R.string.webui_password_too_short, WebUiController.MIN_PASSWORD_LENGTH)) }
                    } else {
                        null
                    },
                    isError = password.isNotEmpty() && tooShort,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.webui_password_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.webui_password_mismatch)) }
                    } else {
                        null
                    },
                    isError = mismatch,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && !tooShort && confirm == password,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            webUi.setPassword(password)
                        } finally {
                            saving = false
                        }
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private const val PORT_COMMIT_DELAY_MILLIS = 800L
