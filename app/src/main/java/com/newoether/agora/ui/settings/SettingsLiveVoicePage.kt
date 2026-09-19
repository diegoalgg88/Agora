package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.assistant.live.VoiceSensitivity
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Live voice-to-voice call settings (plan Phase 4 §10.8): enable gate (default off — preview
 * feature with continuous token cost), conversation reuse, free-text model id and voice name.
 * Includes the explicit privacy + cost notices required by the plan.
 */
@Composable
fun SettingsLiveVoicePage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val enabled by viewModel.settings.liveVoiceEnabled.collectAsState()
    val reuse by viewModel.settings.liveVoiceReuseConversationEnabled.collectAsState()
    val modelId by viewModel.settings.liveVoiceModelId.collectAsState()
    val voiceName by viewModel.settings.liveVoiceVoiceName.collectAsState()
    val sensitivity by viewModel.settings.liveVoiceSensitivity.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_live_voice),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("live-voice.md") },
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_live_voice), items = listOf(
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_live_voice_enable)) },
                        supportingContent = {
                            Text(stringResource(R.string.settings_live_voice_enable_desc))
                        },
                        leadingContent = { Icon(Icons.Default.Mic, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { viewModel.settings.setLiveVoiceEnabled(it) },
                            )
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_live_voice_privacy))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.settings_live_voice_privacy_desc))
                        },
                        leadingContent = {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_live_voice_reuse_conversation))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.settings_live_voice_reuse_conversation_desc,
                                ),
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Forum, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = reuse,
                                onCheckedChange = {
                                    viewModel.settings.setLiveVoiceReuseConversationEnabled(it)
                                },
                            )
                        },
                    )
                },
                {
                    LiveVoiceTextField(
                        icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                        label = stringResource(R.string.settings_live_voice_model),
                        value = modelId,
                        onValueChange = { viewModel.settings.setLiveVoiceModelId(it) },
                    )
                },
                {
                    LiveVoiceTextField(
                        icon = {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                        },
                        label = stringResource(R.string.settings_live_voice_voice_name),
                        value = voiceName,
                        onValueChange = { viewModel.settings.setLiveVoiceVoiceName(it) },
                    )
                },
                {
                    val sensitivityOptions = VoiceSensitivity.entries
                    val labels = listOf(
                        stringResource(R.string.settings_live_voice_sensitivity_patient),
                        stringResource(R.string.settings_live_voice_sensitivity_balanced),
                        stringResource(R.string.settings_live_voice_sensitivity_responsive),
                    )
                    val selectedIndex = sensitivityOptions
                        .indexOf(VoiceSensitivity.fromStorageValue(sensitivity))
                        .coerceAtLeast(0)
                    SettingsItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_live_voice_sensitivity))
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    stringResource(R.string.settings_live_voice_sensitivity_desc),
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                PillTabSwitcher(
                                    tabs = labels,
                                    selectedIndex = selectedIndex,
                                    onSelect = { index ->
                                        viewModel.settings.setLiveVoiceSensitivity(
                                            sensitivityOptions[index].name,
                                        )
                                    },
                                )
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
                    )
                },
            ))
        }
    }
}

/** Plain SettingsItem carrying an inline editable text field (model ids rotate often — the
 *  Live models are preview and must stay free-text, plan §10.8). */
@Composable
private fun LiveVoiceTextField(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    SettingsItem(
        headlineContent = { Text(label) },
        supportingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    onValueChange(it)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                label = { Text(label) },
            )
        },
        leadingContent = icon,
    )
}
