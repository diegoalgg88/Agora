package com.newoether.agora.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * System Assistant settings: default-assistant status + deep link to the system picker, and the
 * overlay behavior toggles. See `development/system-assistant.md`.
 */
@Composable
fun SettingsAssistantPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    val reuseConversation by viewModel.settings.assistantReuseConversationEnabled.collectAsState()
    val attachScreenshot by viewModel.settings.assistantAttachScreenshotEnabled.collectAsState()
    val includeScreenText by viewModel.settings.assistantIncludeScreenTextEnabled.collectAsState()
    val voiceInput by viewModel.settings.assistantVoiceInputEnabled.collectAsState()

    val isDefaultAssistant = rememberDefaultAssistantStatus()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_assistant),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("assistant.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_assistant), items = listOf(
                {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (isDefaultAssistant) {
                                        R.string.settings_assistant_status_active
                                    } else {
                                        R.string.settings_assistant_status_inactive
                                    }
                                )
                            )
                        },
                        leadingContent = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_assistant_open_system_settings)) },
                        leadingContent = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_assistant_reuse_conversation)) },
                        supportingContent = { Text(stringResource(R.string.settings_assistant_reuse_conversation_desc)) },
                        leadingContent = { Icon(Icons.Default.Forum, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = reuseConversation,
                                onCheckedChange = { viewModel.settings.setAssistantReuseConversationEnabled(it) },
                            )
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_assistant_attach_screenshot)) },
                        supportingContent = { Text(stringResource(R.string.settings_assistant_attach_screenshot_desc)) },
                        leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = attachScreenshot,
                                onCheckedChange = { viewModel.settings.setAssistantAttachScreenshotEnabled(it) },
                            )
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_assistant_include_screen_text)) },
                        supportingContent = { Text(stringResource(R.string.settings_assistant_include_screen_text_desc)) },
                        leadingContent = { Icon(Icons.Default.Notes, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = includeScreenText,
                                onCheckedChange = { viewModel.settings.setAssistantIncludeScreenTextEnabled(it) },
                            )
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_assistant_voice_input)) },
                        supportingContent = { Text(stringResource(R.string.settings_assistant_voice_input_desc)) },
                        leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = voiceInput,
                                onCheckedChange = { viewModel.settings.setAssistantVoiceInputEnabled(it) },
                            )
                        },
                    )
                },
            ))
        }
    }
}

/** True when the system currently routes assist gestures to Agora — either through the
 * VoiceInteractionService (stock Android) or through the ACTION_ASSIST activity (One UI). */
@Composable
private fun rememberDefaultAssistantStatus(): Boolean {
    val context = LocalContext.current
    val packageName = context.packageName
    return androidx.compose.runtime.remember {
        val vis = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        val assist = Settings.Secure.getString(context.contentResolver, "assistant")
        vis?.contains(packageName) == true || assist?.contains(packageName) == true
    }
}
