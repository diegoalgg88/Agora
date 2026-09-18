package com.newoether.agora.tool

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure helpers and projections for [AssistantDeviceToolProvider]: everything here is free
 *  of Android dependencies so the parsing/formatting rules are unit-testable (the provider
 *  only supplies clock, zone, flags and cursors). */

@Serializable
internal data class IpLocationResponse(
    val success: Boolean = false,
    val message: String? = null,
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val postal: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: IpTimezoneInfo? = null,
    val connection: IpConnectionInfo? = null,
)

@Serializable
internal data class IpTimezoneInfo(val id: String? = null)

@Serializable
internal data class IpConnectionInfo(val isp: String? = null)

/** Maps a non-2xx status from the IP geolocation service to a stable error code and
 *  human-readable message, so the model can tell a retryable rate limit apart from an
 *  outage instead of seeing a generic "no response". */
internal fun locationHttpError(statusCode: Int): Pair<String, String> = when (statusCode) {
    429 -> "rate_limited" to "Location service rate limit reached, try again later"
    in 500..599 -> "service_error" to "Location service error (HTTP $statusCode)"
    else -> "no_response" to "Location service did not respond (HTTP $statusCode)"
}

internal fun parseIpLocationResponse(body: String): IpLocationResponse? = runCatching {
    Json { ignoreUnknownKeys = true }.decodeFromString<IpLocationResponse>(body)
}.getOrNull()

internal fun buildLocationResult(parsed: IpLocationResponse): String = buildJsonObject {
    put("success", true)
    parsed.city?.let { put("city", it) }
    parsed.region?.let { put("region", it) }
    parsed.country?.let { put("country", it) }
    parsed.countryCode?.let { put("country_code", it) }
    parsed.latitude?.let { put("latitude", it) }
    parsed.longitude?.let { put("longitude", it) }
    parsed.timezone?.id?.let { put("timezone", it) }
    parsed.postal?.let { put("zip", it) }
    parsed.connection?.isp?.let { put("isp", it) }
}.toString()

/** Pure projection of the device clock so time formatting is unit-testable without Android.
 *  Day-of-week and month names follow [locale]; hour cycle follows the caller's 24-hour flag. */
internal fun formatLocalTimeResult(
    now: LocalDateTime,
    zone: ZoneId,
    use24Hour: Boolean,
    locale: Locale,
): String {
    val timePattern = if (use24Hour) "HH:mm" else "h:mm a"
    val display = now.format(
        DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy $timePattern", locale),
    )
    return buildJsonObject {
        put("iso_datetime", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        put("display_datetime", display)
        put("timezone", zone.id)
        put("day_of_week", now.dayOfWeek.getDisplayName(TextStyle.FULL, locale))
    }.toString()
}

/** Parses ISO-8601 datetimes for the calendar tool: offset-qualified and instant forms are
 *  converted to absolute instants; naive forms and date-only values are interpreted in
 *  [zone]. Date-only input yields midnight local time (all-day events re-anchor to UTC
 *  separately). Throws [DateTimeParseException] on unparseable input. */
internal fun parseIsoDateTimeToEpochMs(isoString: String, zone: ZoneId): Long {
    val trimmed = isoString.trim()
    try {
        return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    try {
        return Instant.parse(trimmed).toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    val localFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    )
    for (formatter in localFormatters) {
        try {
            return LocalDateTime.parse(trimmed, formatter)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
    }
    try {
        return java.time.LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
    }
    throw DateTimeParseException("Unable to parse date: $isoString", isoString, 0)
}

/** Re-anchors an all-day event start to UTC midnight of the local calendar date named by
 *  [startMs], the CalendarContract convention (see createCalendarEvent). Pure. */
internal fun anchorAllDayStartUtc(startMs: Long, zone: ZoneId): Long =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone)
        .toLocalDate()
        .atStartOfDay(ZoneId.of("UTC"))
        .toInstant()
        .toEpochMilli()

/** Resolves the query window for list_calendar_events: null start defaults to [nowMs],
 *  null end to start + 7 days. Returns null when end <= start (invalid range). Throws
 *  [DateTimeParseException] on unparseable input. Pure. */
internal fun resolveEventWindow(
    startTime: String?,
    endTime: String?,
    zone: ZoneId,
    nowMs: Long,
): Pair<Long, Long>? {
    val startMs = startTime?.let { parseIsoDateTimeToEpochMs(it, zone) } ?: nowMs
    val endMs = endTime?.let { parseIsoDateTimeToEpochMs(it, zone) }
        ?: (startMs + 7L * 24 * 60 * 60 * 1000)
    if (endMs <= startMs) return null
    return startMs to endMs
}

/** Merges an update's new times with the event's current ones, keeping the existing
 *  duration when only the start moved, and re-anchoring all-day spans to UTC midnight per
 *  the CalendarContract convention. Returns null when the resolved span is non-positive
 *  for a non-all-day event. Pure. */
internal fun resolveUpdateTimes(
    currentStartMs: Long,
    currentEndMs: Long,
    newStartMs: Long?,
    newEndMs: Long?,
    allDay: Boolean,
    zone: ZoneId,
): Pair<Long, Long>? {
    var start = newStartMs ?: currentStartMs
    var end = newEndMs ?: currentEndMs
    if (newStartMs != null && newEndMs == null) {
        // Keep the event's existing duration when only the start moved.
        val duration = currentEndMs - currentStartMs
        end = start + duration
    }
    if (allDay) {
        start = anchorAllDayStartUtc(start, zone)
        if (end <= start) {
            end = start + 24L * 60 * 60 * 1000
        } else {
            end = anchorAllDayStartUtc(end - 1, zone) + 24L * 60 * 60 * 1000
        }
    }
    if (!allDay && end <= start) return null
    return start to end
}

/** Maps weekday names accepted from the model to java.util.Calendar day constants for
 *  AlarmClock.EXTRA_DAYS. Accepts full names, 3-letter abbreviations (any case) and
 *  1-7 = Sun..Sat; any unknown value fails closed as null so a partially-understood
 *  repeat spec never produces a wrong schedule. Pure. */
internal fun parseRepeatDays(days: List<String>?): ArrayList<Int>? {
    if (days == null) return null
    val calendarDays = days.mapNotNull { day -> parseCalendarWeekday(day) }
    if (calendarDays.size != days.size) return null
    return ArrayList(calendarDays)
}

private fun parseCalendarWeekday(day: String): Int? = when (day.trim().lowercase()) {
    "sunday", "sun", "1" -> java.util.Calendar.SUNDAY
    "monday", "mon", "2" -> java.util.Calendar.MONDAY
    "tuesday", "tue", "tues", "3" -> java.util.Calendar.TUESDAY
    "wednesday", "wed", "4" -> java.util.Calendar.WEDNESDAY
    "thursday", "thu", "thur", "thurs", "5" -> java.util.Calendar.THURSDAY
    "friday", "fri", "6" -> java.util.Calendar.FRIDAY
    "saturday", "sat", "7" -> java.util.Calendar.SATURDAY
    else -> null
}

/** One calendar event as read from CalendarContract.Instances, before JSON projection. */
internal data class CalendarEventProjection(
    val id: Long,
    val title: String?,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
)

/** Pure JSON projection of a list_calendar_events result. All-day events render as bare
 *  dates (CalendarContract stores them as UTC-midnight spans, so project back through the
 *  local zone); timed events render as zone-local ISO datetimes. Pure. */
internal fun buildCalendarEventsResult(
    events: List<CalendarEventProjection>,
    zone: ZoneId,
    limit: Int,
): String = buildJsonObject {
    put("success", true)
    put("count", events.size)
    if (events.size >= limit) put("truncated", true)
    put(
        "events",
        kotlinx.serialization.json.buildJsonArray {
            for (event in events) {
                add(
                    buildJsonObject {
                        put("event_id", event.id)
                        event.title?.let { put("title", it) }
                        if (event.allDay) {
                            put(
                                "date",
                                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.startMs), zone)
                                    .toLocalDate()
                                    .toString(),
                            )
                            put("all_day", true)
                        } else {
                            put(
                                "start",
                                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.startMs), zone)
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            )
                            put(
                                "end",
                                LocalDateTime.ofInstant(Instant.ofEpochMilli(event.endMs), zone)
                                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            )
                        }
                        event.location?.let { put("location", it) }
                        event.description?.let { put("description", it) }
                    },
                )
            }
        },
    )
}.toString()

/** Human-readable one-line summary of a staged destructive action, rendered in the
 *  approval banner. Returns null when the arguments are too incomplete to describe —
 *  staging then fails closed instead of queueing something the user can't review.
 *  Pure. */
internal fun buildAssistantActionSummary(
    name: String,
    args: Map<String, kotlinx.serialization.json.JsonElement>,
): String? {
    fun str(key: String): String? =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    fun int(key: String): Int? = str(key)?.toIntOrNull()
    fun days(key: String): String? =
        (args[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

    return when (name) {
        "set_alarm" -> {
            val label = str("label")
            val days = days("days")
            when {
                int("hour") != null && int("minutes") != null -> {
                    val time = "%02d:%02d".format(int("hour")!!, int("minutes")!!)
                    val repeat = days?.let { ", repeats $it" } ?: ""
                    "Set alarm at $time$repeat" + (label?.let { " — \"$it\"" } ?: "")
                }
                int("duration_seconds") != null -> {
                    val mins = int("duration_seconds")!! / 60
                    val human = if (mins >= 1) "$mins min" else "${int("duration_seconds")} s"
                    "Start timer: $human" + (label?.let { " — \"$it\"" } ?: "")
                }
                else -> null
            }
        }
        "create_calendar_event" -> {
            val title = str("title") ?: return null
            val whenText = str("start_time") ?: return null
            val end = str("end_time")?.let { " → $it" } ?: ""
            "Create event \"$title\" at $whenText$end"
        }
        "update_calendar_event" -> {
            val id = str("event_id") ?: return null
            val changes = listOf(
                str("title")?.let { "title → \"$it\"" },
                str("start_time")?.let { "start → $it" },
                str("end_time")?.let { "end → $it" },
                str("location")?.let { "location → $it" },
            ).filterNotNull().joinToString(", ")
            if (changes.isEmpty()) null else "Update event $id: $changes"
        }
        "delete_calendar_event" -> {
            val id = str("event_id") ?: return null
            "Delete event $id"
        }
        else -> null
    }
}

/** Interprets an approved execution's JSON result for the store: (succeeded, error).
 *  Pure. */
internal fun parseAssistantActionResult(result: String): Pair<Boolean, String?> {
    val parsed = runCatching {
        Json.parseToJsonElement(result).let { it as? kotlinx.serialization.json.JsonObject }
    }.getOrNull() ?: return false to "Unreadable execution result"
    val success = (parsed["success"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.toBoolean() ?: false
    if (success) return true to null
    val error = (parsed["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val message = (parsed["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return false to listOfNotNull(error, message).joinToString(": ").ifBlank { "Execution failed" }
}
