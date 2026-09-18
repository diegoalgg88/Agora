package com.newoether.agora.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Tools settings screen. Tools that have their own settings page (web search, conversation
 * search, skills, memory, image generation, shell, MCP, automation) are configured there —
 * never duplicated here. This screen hosts only the assistant device-action tools, which
 * have no configuration beyond on/off (see development/assistant-device-tools.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsToolsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()

    val assistantSetAlarm by viewModel.settings.assistantSetAlarmEnabled.collectAsState()
    val assistantOpenFile by viewModel.settings.assistantOpenFileEnabled.collectAsState()
    val assistantCalendar by viewModel.settings.assistantCreateCalendarEventEnabled.collectAsState()
    val assistantListCalendar by viewModel.settings.assistantListCalendarEventsEnabled.collectAsState()
    val assistantUpdateCalendar by viewModel.settings.assistantUpdateCalendarEventEnabled.collectAsState()
    val assistantDeleteCalendar by viewModel.settings.assistantDeleteCalendarEventEnabled.collectAsState()
    val assistantLocation by viewModel.settings.assistantGetLocationEnabled.collectAsState()
    val assistantLocalTime by viewModel.settings.assistantGetLocalTimeEnabled.collectAsState()
    val assistantOpenUrl by viewModel.settings.assistantOpenUrlEnabled.collectAsState()
    val assistantNotification by viewModel.settings.assistantSendNotificationEnabled.collectAsState()

    // Enabling any calendar tool requires the runtime calendar permissions; the toggle
    // only sticks when the user grants both. One launcher serves all four calendar rows —
    // the pending setter records which toggle triggered the request.
    var pendingCalendarSetter by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[android.Manifest.permission.READ_CALENDAR] == true &&
            grants[android.Manifest.permission.WRITE_CALENDAR] == true
        pendingCalendarSetter?.invoke(granted)
        pendingCalendarSetter = null
    }
    fun requestCalendarThen(setter: (Boolean) -> Unit) {
        pendingCalendarSetter = setter
        calendarPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR,
            )
        )
    }
    fun calendarToggle(current: Boolean, enable: (Boolean) -> Unit) {
        if (!current) requestCalendarThen(enable) else enable(false)
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_tools),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("tools.md") }
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.settings_tools_group_assistant), items = listOf(
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_set_alarm)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_set_alarm_desc)) },
                        leadingContent = { Icon(Icons.Default.Alarm, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantSetAlarm, onCheckedChange = { viewModel.settings.setAssistantSetAlarmEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantSetAlarmEnabled(!assistantSetAlarm) }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_open_file)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_open_file_desc)) },
                        leadingContent = { Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantOpenFile, onCheckedChange = { viewModel.settings.setAssistantOpenFileEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantOpenFileEnabled(!assistantOpenFile) }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_create_calendar_event)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_create_calendar_event_desc)) },
                        leadingContent = { Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = assistantCalendar, onCheckedChange = {
                                calendarToggle(assistantCalendar) { viewModel.settings.setAssistantCreateCalendarEventEnabled(it) }
                            })
                        },
                        modifier = Modifier.clickable {
                            calendarToggle(assistantCalendar) { viewModel.settings.setAssistantCreateCalendarEventEnabled(it) }
                        }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_list_calendar_events)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_list_calendar_events_desc)) },
                        leadingContent = { Icon(Icons.Default.EventNote, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = assistantListCalendar, onCheckedChange = {
                                calendarToggle(assistantListCalendar) { viewModel.settings.setAssistantListCalendarEventsEnabled(it) }
                            })
                        },
                        modifier = Modifier.clickable {
                            calendarToggle(assistantListCalendar) { viewModel.settings.setAssistantListCalendarEventsEnabled(it) }
                        }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_update_calendar_event)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_update_calendar_event_desc)) },
                        leadingContent = { Icon(Icons.Default.EditCalendar, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = assistantUpdateCalendar, onCheckedChange = {
                                calendarToggle(assistantUpdateCalendar) { viewModel.settings.setAssistantUpdateCalendarEventEnabled(it) }
                            })
                        },
                        modifier = Modifier.clickable {
                            calendarToggle(assistantUpdateCalendar) { viewModel.settings.setAssistantUpdateCalendarEventEnabled(it) }
                        }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_delete_calendar_event)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_delete_calendar_event_desc)) },
                        leadingContent = { Icon(Icons.Default.EventBusy, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = assistantDeleteCalendar, onCheckedChange = {
                                calendarToggle(assistantDeleteCalendar) { viewModel.settings.setAssistantDeleteCalendarEventEnabled(it) }
                            })
                        },
                        modifier = Modifier.clickable {
                            calendarToggle(assistantDeleteCalendar) { viewModel.settings.setAssistantDeleteCalendarEventEnabled(it) }
                        }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_get_location)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_get_location_desc)) },
                        leadingContent = { Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantLocation, onCheckedChange = { viewModel.settings.setAssistantGetLocationEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantGetLocationEnabled(!assistantLocation) }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_get_local_time)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_get_local_time_desc)) },
                        leadingContent = { Icon(Icons.Default.AccessTime, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantLocalTime, onCheckedChange = { viewModel.settings.setAssistantGetLocalTimeEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantGetLocalTimeEnabled(!assistantLocalTime) }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_open_url)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_open_url_desc)) },
                        leadingContent = { Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantOpenUrl, onCheckedChange = { viewModel.settings.setAssistantOpenUrlEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantOpenUrlEnabled(!assistantOpenUrl) }
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.settings_tool_send_notification)) },
                        supportingContent = { Text(stringResource(R.string.settings_tool_send_notification_desc)) },
                        leadingContent = { Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Switch(checked = assistantNotification, onCheckedChange = { viewModel.settings.setAssistantSendNotificationEnabled(it) }) },
                        modifier = Modifier.clickable { viewModel.settings.setAssistantSendNotificationEnabled(!assistantNotification) }
                    )
                }
            ))
        }
    }
}
