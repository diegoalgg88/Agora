package com.newoether.agora.tool

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Assistant device-action tools: single-shot, user-visible actions performed on the
 * user's behalf (alarm, calendar event, open file/URL, local time, IP location,
 * notification). No streaming, no persistent state; every execution ends in one
 * completed result.
 *
 * Each tool is gated per request in [definitions] by its own Settings toggle
 * (default off), so a disabled tool never reaches the model's tool list.
 * `open_file` additionally requires the sandbox flavor; `create_calendar_event`
 * requires the runtime calendar permissions at execution time.
 */
class AssistantDeviceToolProvider(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val sandboxFactory: SandboxManagerFactory?,
    private val actionStore: com.newoether.agora.data.AssistantActionStore,
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }
    // Notification IDs for the assistant channel, offset far above every other fixed ID in
    // the app (foreground service = 1, heartbeat/auto-backup = 1001, live voice = 424) so a
    // posted notification can never replace or be replaced by an unrelated system one.
    private val notificationIdCounter = AtomicInteger(0)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = buildList {
        if (settingsRepository.assistantSetAlarmEnabled.value) add(SET_ALARM)
        if (settingsRepository.assistantOpenFileEnabled.value && sandboxHomeDir() != null) {
            add(OPEN_FILE)
        }
        if (settingsRepository.assistantCreateCalendarEventEnabled.value) add(CREATE_CALENDAR_EVENT)
        if (settingsRepository.assistantListCalendarEventsEnabled.value) add(LIST_CALENDAR_EVENTS)
        if (settingsRepository.assistantUpdateCalendarEventEnabled.value) add(UPDATE_CALENDAR_EVENT)
        if (settingsRepository.assistantDeleteCalendarEventEnabled.value) add(DELETE_CALENDAR_EVENT)
        if (settingsRepository.assistantGetLocationEnabled.value) add(GET_LOCATION_FROM_IP)
        if (settingsRepository.assistantGetLocalTimeEnabled.value) add(GET_LOCAL_TIME)
        if (settingsRepository.assistantOpenUrlEnabled.value) add(OPEN_URL)
        if (settingsRepository.assistantSendNotificationEnabled.value) add(SEND_NOTIFICATION)
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val args = json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        try {
            // Destructive device actions are staged for user approval, never executed
            // from the AI path (owner decision 2026-09-17, mirrors the SMS draft flow).
            if (name in DESTRUCTIVE_TOOL_NAMES) {
                stageDestructiveAction(name, arguments, args)
            } else {
                dispatchTool(name, args)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson("execution_error", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Executes a staged action the user approved in the chat banner. Same dispatch and
     * validations as [execute]; only [AssistantActionStore.approveAction] calls this —
     * never the AI path.
     */
    suspend fun runApprovedAction(type: String, argumentsJson: String): String =
        withContext(Dispatchers.IO) {
            val args = json.decodeFromString<Map<String, JsonElement>>(argumentsJson.ifBlank { "{}" })
            try {
                dispatchTool(type, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorJson("execution_error", e.message ?: e.javaClass.simpleName)
            }
        }

    /** Raw tool dispatch shared by the read-only AI path and the approved-execution path. */
    private fun dispatchTool(name: String, args: Map<String, JsonElement>): String {
        fun arg(key: String): String? = (args[key] as? JsonPrimitive)?.content
        fun argInt(key: String): Int? = (args[key] as? JsonPrimitive)?.content?.toIntOrNull()
        fun argBool(key: String): Boolean? =
            (args[key] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }
        fun argLong(key: String): Long? = arg(key)?.toLongOrNull()
        fun argStringList(key: String): List<String>? =
            (args[key] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.takeIf { it.isNotEmpty() }

        return when (name) {
                "set_alarm" -> setAlarm(
                    hour = argInt("hour"),
                    minutes = argInt("minutes"),
                    label = arg("label"),
                    durationSeconds = argInt("duration_seconds"),
                    days = argStringList("days"),
                )
                "open_file" -> openFile(arg("path"))
                "create_calendar_event" -> createCalendarEvent(
                    title = arg("title"),
                    startTime = arg("start_time"),
                    endTime = arg("end_time"),
                    description = arg("description"),
                    location = arg("location"),
                    allDay = argBool("all_day") ?: false,
                    reminderMinutes = argInt("reminder_minutes") ?: 15,
                )
                "list_calendar_events" -> listCalendarEvents(
                    startTime = arg("start_time"),
                    endTime = arg("end_time"),
                    maxResults = argInt("max_results"),
                )
                "update_calendar_event" -> updateCalendarEvent(
                    eventId = argLong("event_id"),
                    title = arg("title"),
                    startTime = arg("start_time"),
                    endTime = arg("end_time"),
                    description = arg("description"),
                    location = arg("location"),
                    allDay = argBool("all_day"),
                )
                "delete_calendar_event" -> deleteCalendarEvent(argLong("event_id"))
                "get_location_from_ip" -> getLocationFromIp()
                "get_local_time" -> getLocalTime()
                "open_url" -> openUrl(arg("url"))
                "send_notification" -> sendNotification(arg("title"), arg("message"))
                else -> errorJson("unknown_tool", name)
        }
    }

    // ── staged destructive actions ─────────────────────────

    /** Stages a destructive action for the user to approve. The summary is the pure
     *  projection the banner renders; staging validates arguments first (fail-closed
     *  against model mistakes before anything reaches the queue). */
    private suspend fun stageDestructiveAction(
        name: String,
        arguments: String,
        args: Map<String, JsonElement>,
    ): String {
        val summary = buildAssistantActionSummary(name, args)
            ?: return errorJson("invalid_arguments", "Could not build a review summary for $name")
        val action = com.newoether.agora.data.AssistantAction(
            type = name,
            argumentsJson = arguments,
            summary = summary,
        )
        actionStore.addAction(action)
        return buildJsonObject {
            put("success", true)
            put("staged", true)
            put("action_id", action.id)
            put("summary", summary)
            put(
                "message",
                "Action staged for user approval. Nothing runs until the user taps Approve " +
                    "in the chat banner. Tell the user what you staged and ask them to review.",
            )
        }.toString()
    }

    // ── set_alarm ──────────────────────────────────────────

    private fun setAlarm(
        hour: Int?,
        minutes: Int?,
        label: String?,
        durationSeconds: Int?,
        days: List<String>?,
    ): String {
        val intent = when {
            // A clock time always means a wake-up alarm; never downgrade it to a countdown.
            hour != null && minutes != null -> {
                if (hour !in 0..23 || minutes !in 0..59) {
                    return errorJson(
                        "invalid_arguments",
                        "hour must be 0-23 and minutes must be 0-59",
                    )
                }
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    // Fail closed on an unparseable repeat spec: a silently one-time
                    // alarm would betray a "every monday" request.
                    if (days != null) {
                        val repeatDays = parseRepeatDays(days)
                            ?: return errorJson(
                                "invalid_arguments",
                                "days must be weekday names, e.g. [\"monday\", \"friday\"]",
                            )
                        putExtra(AlarmClock.EXTRA_DAYS, repeatDays)
                    }
                }
            }
            durationSeconds != null -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            else -> return errorJson(
                "missing_arguments",
                "Provide either hour+minutes for an alarm or duration_seconds for a timer",
            )
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                if (hour != null && minutes != null) {
                    put("type", "alarm")
                    put("hour", hour)
                    put("minutes", minutes)
                } else {
                    put("type", "timer")
                    put("duration_seconds", durationSeconds!!)
                }
            }.toString()
        } catch (e: ActivityNotFoundException) {
            errorJson("no_clock_app", "No clock app available to handle the request")
        } catch (e: SecurityException) {
            errorJson("alarm_permission_missing", "Missing com.android.alarm.permission.SET_ALARM")
        }
    }

    // ── open_file ──────────────────────────────────────────

    private fun sandboxHomeDir(): File? =
        sandboxFactory?.takeIf { it.isAvailable() }?.create()?.getSandboxHomeDir()

    private fun openFile(path: String?): String {
        if (path.isNullOrBlank()) return errorJson("missing_path", "path is required")
        val home = sandboxHomeDir()
            ?: return errorJson("sandbox_unavailable", "The sandbox is not available in this build")
        val trimmed = path.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            return errorJson("invalid_path", "Path must be relative to the sandbox home, without leading /")
        }
        val parts = trimmed.split("/", "\\").filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            return errorJson("invalid_path", "Path must not contain .. segments")
        }
        val file = parts.fold(home) { dir, part -> File(dir, part) }
        if (!file.exists()) return errorJson("not_found", "File not found: $path")
        if (!file.isFile) return errorJson("not_a_file", "Not a file: $path")

        val mimeType = guessMimeType(file.name)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("path", path)
                put("mime_type", mimeType)
            }.toString()
        } catch (e: ActivityNotFoundException) {
            errorJson("no_handler", "No app can open $mimeType files")
        }
    }

    // ── create_calendar_event ──────────────────────────────

    private fun hasCalendarPermission(): Boolean {
        val read = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    private fun primaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                if (idIndex >= 0) return cursor.getLong(idIndex)
            }
        }
        return null
    }

    private fun createCalendarEvent(
        title: String?,
        startTime: String?,
        endTime: String?,
        description: String?,
        location: String?,
        allDay: Boolean,
        reminderMinutes: Int,
    ): String {
        if (title.isNullOrBlank()) return errorJson("missing_title", "title is required")
        if (startTime.isNullOrBlank()) return errorJson("missing_start_time", "start_time is required")
        if (!hasCalendarPermission()) {
            return errorJson(
                "permission_denied",
                "Calendar permission not granted. Ask the user to enable it from Settings → Tools.",
            )
        }
        val calendarId = primaryCalendarId()
            ?: return errorJson("no_calendar", "No writable calendar found on the device")

        val startMillis: Long
        val endMillis: Long
        try {
            startMillis = parseIsoDateTimeToEpochMs(startTime, ZoneId.systemDefault())
            endMillis = endTime?.let { parseIsoDateTimeToEpochMs(it, ZoneId.systemDefault()) }
                ?: (startMillis + 60 * 60 * 1000)
        } catch (e: DateTimeParseException) {
            return errorJson("invalid_date", "Use ISO 8601, e.g. 2024-03-15T14:30:00")
        }
        if (!allDay && endMillis <= startMillis) {
            return errorJson(
                "end_before_start",
                "end_time must be after start_time",
            )
        }

        // CalendarContract expects all-day events anchored at UTC midnight so OEM calendar
        // apps render them on the same local day the user named; naive inputs were parsed
        // in the device zone, so re-anchor by calendar date.
        val effectiveStartMillis = if (allDay) anchorAllDayStartUtc(startMillis, ZoneId.systemDefault()) else startMillis
        val effectiveEndMillis = if (allDay) {
            effectiveStartMillis + 24 * 60 * 60 * 1000
        } else endMillis

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, effectiveStartMillis)
            put(CalendarContract.Events.DTEND, effectiveEndMillis)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (allDay) "UTC" else TimeZone.getDefault().id,
            )
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val eventId = uri?.lastPathSegment?.toLongOrNull()
            ?: return errorJson("insert_failed", "Failed to create calendar event")

        val reminderFailed = reminderMinutes > 0 && runCatching {
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
        }.getOrNull() == null

        return buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("title", title)
            put("start", startTime)
            if (reminderFailed) {
                put("warning", "Event created, but the reminder could not be added")
            }
        }.toString()
    }

    // ── list_calendar_events ──────────────────────────────

    private fun listCalendarEvents(startTime: String?, endTime: String?, maxResults: Int?): String {
        if (!hasCalendarPermission()) {
            return errorJson(
                "permission_denied",
                "Calendar permission not granted. Ask the user to enable it from Settings → Tools.",
            )
        }
        val zone = ZoneId.systemDefault()
        val window = try {
            resolveEventWindow(startTime, endTime, zone, System.currentTimeMillis())
        } catch (e: DateTimeParseException) {
            return errorJson("invalid_date", "Use ISO 8601, e.g. 2024-03-15T14:30:00")
        } ?: return errorJson("invalid_range", "end_time must be after start_time")
        val limit = maxResults?.coerceIn(1, MAX_CALENDAR_RESULTS) ?: DEFAULT_CALENDAR_RESULTS

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        val events = mutableListOf<CalendarEventProjection>()
        CalendarContract.Instances.query(context.contentResolver, projection, window.first, window.second)
            ?.use { cursor ->
                val id = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val title = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val begin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val end = cursor.getColumnIndex(CalendarContract.Instances.END)
                val allDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val location = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val description = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                while (cursor.moveToNext() && events.size < limit) {
                    events.add(
                        CalendarEventProjection(
                            id = if (id >= 0) cursor.getLong(id) else -1L,
                            title = if (title >= 0) cursor.getString(title) else null,
                            startMs = if (begin >= 0) cursor.getLong(begin) else 0L,
                            endMs = if (end >= 0) cursor.getLong(end) else 0L,
                            allDay = allDay >= 0 && cursor.getInt(allDay) != 0,
                            location = if (location >= 0) cursor.getString(location) else null,
                            description = if (description >= 0) cursor.getString(description) else null,
                        ),
                    )
                }
            }
        return buildCalendarEventsResult(events, zone, limit)
    }

    // ── update_calendar_event ─────────────────────────────

    private fun updateCalendarEvent(
        eventId: Long?,
        title: String?,
        startTime: String?,
        endTime: String?,
        description: String?,
        location: String?,
        allDay: Boolean?,
    ): String {
        if (eventId == null) return errorJson("missing_event_id", "event_id is required")
        val hasChange = title != null || startTime != null || endTime != null ||
            description != null || location != null || allDay != null
        if (!hasChange) {
            return errorJson("no_changes", "Provide at least one field to update")
        }
        if (!hasCalendarPermission()) {
            return errorJson(
                "permission_denied",
                "Calendar permission not granted. Ask the user to enable it from Settings → Tools.",
            )
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
        )
        val current: Triple<Long, Long, Boolean>? =
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                projection, null, null, null,
            )?.use { cursor ->
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Events.ALL_DAY)
                if (cursor.moveToFirst() && startIdx >= 0 && endIdx >= 0 && allDayIdx >= 0) {
                    Triple(cursor.getLong(startIdx), cursor.getLong(endIdx), cursor.getInt(allDayIdx) != 0)
                } else null
            }
        if (current == null) return errorJson("not_found", "No calendar event with id $eventId")

        val zone = ZoneId.systemDefault()
        val effectiveAllDay = allDay ?: current.third
        val resolvedTimes = try {
            resolveUpdateTimes(
                currentStartMs = current.first,
                currentEndMs = current.second,
                newStartMs = startTime?.let { parseIsoDateTimeToEpochMs(it, zone) },
                newEndMs = endTime?.let { parseIsoDateTimeToEpochMs(it, zone) },
                allDay = effectiveAllDay,
                zone = zone,
            )
        } catch (e: DateTimeParseException) {
            return errorJson("invalid_date", "Use ISO 8601, e.g. 2024-03-15T14:30:00")
        } ?: return errorJson("end_before_start", "end_time must be after start_time")

        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (startTime != null || endTime != null || allDay != null) {
                put(CalendarContract.Events.DTSTART, resolvedTimes.first)
                put(CalendarContract.Events.DTEND, resolvedTimes.second)
                put(CalendarContract.Events.ALL_DAY, if (effectiveAllDay) 1 else 0)
                put(
                    CalendarContract.Events.EVENT_TIMEZONE,
                    if (effectiveAllDay) "UTC" else TimeZone.getDefault().id,
                )
            }
        }
        val rows = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            values, null, null,
        )
        if (rows <= 0) return errorJson("update_failed", "Failed to update calendar event")
        return buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            if (title != null) put("title", title)
            if (startTime != null) put("start", startTime)
            if (endTime != null) put("end", endTime)
            if (allDay != null) put("all_day", allDay)
        }.toString()
    }

    // ── delete_calendar_event ─────────────────────────────

    private fun deleteCalendarEvent(eventId: Long?): String {
        if (eventId == null) return errorJson("missing_event_id", "event_id is required")
        if (!hasCalendarPermission()) {
            return errorJson(
                "permission_denied",
                "Calendar permission not granted. Ask the user to enable it from Settings → Tools.",
            )
        }
        val rows = context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            null, null,
        )
        if (rows <= 0) return errorJson("not_found", "No calendar event with id $eventId")
        return buildJsonObject {
            put("success", true)
            put("event_id", eventId)
            put("deleted", true)
        }.toString()
    }

    // ── get_location_from_ip ─────────────────────────────

    private fun getLocationFromIp(): String {
        val response = HttpClient.fetchModelsResponse(
            "https://ipwho.is/",
            mapOf("User-Agent" to Constants.WEB_FETCH_USER_AGENT),
            callTimeoutMillis = 10_000,
        )
        if (!response.isSuccessful) {
            val (code, message) = locationHttpError(response.code)
            return errorJson(code, message)
        }
        val parsed = parseIpLocationResponse(response.body)
            ?: return errorJson("parse_error", "Could not parse location response")
        if (!parsed.success) {
            return errorJson("lookup_failed", parsed.message ?: "IP location lookup failed")
        }
        return buildLocationResult(parsed)
    }

    // ── get_local_time ─────────────────────────────────────

    private fun getLocalTime(): String {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        return formatLocalTimeResult(
            now = now,
            zone = zone,
            use24Hour = android.text.format.DateFormat.is24HourFormat(context),
            locale = Locale.getDefault(),
        )
    }

    // ── open_url ───────────────────────────────────────────

    private fun openUrl(url: String?): String {
        if (url.isNullOrBlank()) return errorJson("missing_url", "url is required")
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            ?: return errorJson("invalid_url", "Could not parse URL")
        if (uri.scheme != "http" && uri.scheme != "https") {
            return errorJson("invalid_scheme", "Only http and https URLs are allowed")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            buildJsonObject {
                put("success", true)
                put("url", url)
            }.toString()
        } catch (e: ActivityNotFoundException) {
            errorJson("no_handler", "No app can open this URL")
        }
    }

    // ── send_notification ──────────────────────────────────

    private fun sendNotification(title: String?, message: String?): String {
        if (message.isNullOrBlank()) return errorJson("missing_message", "message is required")
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return errorJson(
                "notifications_disabled",
                "Notifications are disabled for this app. Ask the user to enable them in system settings.",
            )
        }
        ensureNotificationChannel()
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = ASSISTANT_NOTIFICATION_ID_BASE + notificationIdCounter.incrementAndGet()
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ASSISTANT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: context.getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return buildJsonObject {
            put("success", true)
            put("notification_id", notificationId)
        }.toString()
    }

    private fun ensureNotificationChannel() {
        val channel = android.app.NotificationChannel(
            ASSISTANT_CHANNEL_ID,
            "Assistant Actions",
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications posted by the AI assistant on your behalf"
        }
        context.getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // ── Helpers ────────────────────────────────────────────

    private fun errorJson(code: String, message: String): String = buildJsonObject {
        put("success", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        MIME_OVERRIDES[ext]?.let { return it }
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        return "text/plain"
    }

    companion object {
        private const val ASSISTANT_CHANNEL_ID = "assistant_actions"
        private const val ASSISTANT_NOTIFICATION_ID_BASE = 10_000
        private const val DEFAULT_CALENDAR_RESULTS = 25
        private const val MAX_CALENDAR_RESULTS = 50

        private val TOOL_NAMES = setOf(
            "set_alarm",
            "open_file",
            "create_calendar_event",
            "list_calendar_events",
            "update_calendar_event",
            "delete_calendar_event",
            "get_location_from_ip",
            "get_local_time",
            "open_url",
            "send_notification",
        )

        /** Actions with a lasting real-world effect; staged for user approval instead of
         *  executed from the AI path (read-only tools stay direct). */
        private val DESTRUCTIVE_TOOL_NAMES = setOf(
            "set_alarm",
            "create_calendar_event",
            "update_calendar_event",
            "delete_calendar_event",
        )

        // Android's MIME map misses or misclassifies some sandbox-produced extensions.
        private val MIME_OVERRIDES = mapOf(
            "apk" to "application/vnd.android.package-archive",
            "ts" to "text/plain",
            "md" to "text/markdown",
        )

        private val SET_ALARM = ToolDefinition(
            function = ToolFunction(
                name = "set_alarm",
                description = "Stage an alarm or countdown timer on the device. The action is " +
                    "queued for user approval — a banner appears in the chat and nothing runs " +
                    "until the user taps Approve. After calling, tell the user what you staged " +
                    "and ask them to review. " +
                    "IMPORTANT: when the user names a clock time (e.g. 'wake me at 7:30', " +
                    "'alarm for 9pm') ALWAYS provide hour and minutes — that creates a real " +
                    "clock alarm. Only use duration_seconds when the user explicitly asks " +
                    "for a countdown (e.g. 'remind me in 10 minutes'). Never convert a " +
                    "named clock time into duration_seconds.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "hour" to ToolProperty("integer", "Hour of the alarm in 24-hour format (0-23). Use this mode for alarms at a specific clock time."),
                        "minutes" to ToolProperty("integer", "Minutes of the alarm (0-59). Required together with hour."),
                        "label" to ToolProperty("string", "Label for the alarm or timer"),
                        "days" to ToolProperty(
                            "array",
                            "Days of the week the alarm repeats, e.g. [\"monday\", \"friday\"] or [\"mon\", \"fri\"]. Omit for a one-time alarm.",
                            items = ToolProperty("string", "Weekday name: monday, tuesday, wednesday, thursday, friday, saturday, sunday (or 3-letter abbreviation)"),
                        ),
                        "duration_seconds" to ToolProperty("integer", "Duration in seconds — ONLY for explicit countdown timers, never for clock-time alarms"),
                    ),
                    required = emptyList(),
                ),
            ),
        )

        private val OPEN_FILE = ToolDefinition(
            function = ToolFunction(
                name = "open_file",
                description = "Open a file from the sandbox home directory in the user's default Android app — browser for HTML, image viewer for PNG/JPG, PDF viewer for PDF, etc. This is how you show finished work to the user. Path is relative to the sandbox home. Prefer self-contained files (inline CSS/JS for HTML).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "path" to ToolProperty("string", "Path relative to the sandbox home, e.g. site/index.html or notes.md"),
                    ),
                    required = listOf("path"),
                ),
            ),
        )

        private val CREATE_CALENDAR_EVENT = ToolDefinition(
            function = ToolFunction(
                name = "create_calendar_event",
                description = "Stage a new calendar event on the user's device. The event is " +
                    "queued for user approval — a banner appears in the chat and nothing is " +
                    "written until the user taps Approve. After calling, tell the user what " +
                    "you staged and ask them to review.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Event title"),
                        "start_time" to ToolProperty("string", "Start time as ISO 8601, e.g. '2024-03-15T14:30:00+02:00' or '2024-03-15' for all-day events. Naive (no offset) is treated as the user's local time."),
                        "end_time" to ToolProperty("string", "End time, same format as start_time. Defaults to 1 hour after start (ignored for all-day events)."),
                        "description" to ToolProperty("string", "Event notes or description"),
                        "location" to ToolProperty("string", "Event location"),
                        "all_day" to ToolProperty("boolean", "Whether this is an all-day event"),
                        "reminder_minutes" to ToolProperty("integer", "Minutes before event to send reminder (default: 15)"),
                    ),
                    required = listOf("title", "start_time"),
                ),
            ),
        )

        private val LIST_CALENDAR_EVENTS = ToolDefinition(
            function = ToolFunction(
                name = "list_calendar_events",
                description = "List upcoming (or ranged) calendar events on the user's device. Use this when the user asks what's on their calendar/schedule.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "start_time" to ToolProperty("string", "Window start as ISO 8601. Defaults to now."),
                        "end_time" to ToolProperty("string", "Window end as ISO 8601. Defaults to start + 7 days."),
                        "max_results" to ToolProperty("integer", "Maximum events to return, 1-50 (default: 25)"),
                    ),
                    required = emptyList(),
                ),
            ),
        )

        private val UPDATE_CALENDAR_EVENT = ToolDefinition(
            function = ToolFunction(
                name = "update_calendar_event",
                description = "Stage an update to a calendar event identified by event_id. Only the fields you provide are changed. The change is queued for user approval — a banner appears in the chat and nothing is written until the user taps Approve. Get the event_id from create_calendar_event results or list_calendar_events.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "event_id" to ToolProperty("integer", "ID of the event to update"),
                        "title" to ToolProperty("string", "New event title"),
                        "start_time" to ToolProperty("string", "New start time as ISO 8601. Changing the start keeps the event's duration unless end_time is also given."),
                        "end_time" to ToolProperty("string", "New end time as ISO 8601"),
                        "description" to ToolProperty("string", "New event notes or description"),
                        "location" to ToolProperty("string", "New event location"),
                        "all_day" to ToolProperty("boolean", "Whether this is an all-day event"),
                    ),
                    required = listOf("event_id"),
                ),
            ),
        )

        private val DELETE_CALENDAR_EVENT = ToolDefinition(
            function = ToolFunction(
                name = "delete_calendar_event",
                description = "Stage deletion of a calendar event identified by event_id. The deletion is queued for user approval — a banner appears in the chat and nothing is removed until the user taps Approve. This cannot be undone once approved. Get the event_id from create_calendar_event results or list_calendar_events.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "event_id" to ToolProperty("integer", "ID of the event to delete"),
                    ),
                    required = listOf("event_id"),
                ),
            ),
        )

        private val GET_LOCATION_FROM_IP = ToolDefinition(
            function = ToolFunction(
                name = "get_location_from_ip",
                description = "Get the user's approximate location based on their IP address (city-level, reflects the network egress point, not GPS). Returns city, region, country, coordinates, and timezone.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList()),
            ),
        )

        private val GET_LOCAL_TIME = ToolDefinition(
            function = ToolFunction(
                name = "get_local_time",
                description = "Get the current local date and time. Call this first when the user mentions relative dates like 'tomorrow', 'next week', 'in 2 hours', etc.",
                parameters = ToolParameters(properties = emptyMap(), required = emptyList()),
            ),
        )

        private val OPEN_URL = ToolDefinition(
            function = ToolFunction(
                name = "open_url",
                description = "Open a URL in the user's browser or default app. This ONLY opens the link for the user to view — you will NOT receive the page content back. Do not use this to fetch or read information from URLs (use web_fetch for that). Use this when the user asks to open or visit a link.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "The URL to open"),
                    ),
                    required = listOf("url"),
                ),
            ),
        )

        private val SEND_NOTIFICATION = ToolDefinition(
            function = ToolFunction(
                name = "send_notification",
                description = "Send a push notification to the device",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Notification title"),
                        "message" to ToolProperty("string", "Notification content/body"),
                    ),
                    required = listOf("message"),
                ),
            ),
        )
    }
}
