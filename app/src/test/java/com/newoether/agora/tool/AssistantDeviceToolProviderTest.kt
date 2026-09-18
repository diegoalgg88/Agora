package com.newoether.agora.tool

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDeviceToolProviderTest {

    // ── locationHttpError ─────────────────────────────────

    @Test
    fun rateLimitMapsToRetryableErrorCode() {
        val (code, _) = locationHttpError(429)
        assertEquals("rate_limited", code)
    }

    @Test
    fun serverErrorsMapToServiceErrorWithStatus() {
        val (code, message) = locationHttpError(503)
        assertEquals("service_error", code)
        assertTrue(message.contains("503"))
    }

    @Test
    fun otherFailuresMapToNoResponseWithStatus() {
        val (code, message) = locationHttpError(403)
        assertEquals("no_response", code)
        assertTrue(message.contains("403"))
    }

    // ── parseIpLocationResponse ────────────────────────────

    @Test
    fun parsesSuccessfulLookupAndIgnoresUnknownFields() {
        val body = """
            {
              "ip": "203.0.113.7",
              "success": true,
              "city": "Monterrey",
              "region": "Nuevo León",
              "country": "Mexico",
              "country_code": "MX",
              "postal": "64000",
              "latitude": 25.6866,
              "longitude": -100.3161,
              "timezone": {"id": "America/Monterrey", "utc": "-06:00"},
              "connection": {"isp": "Example Telecom"},
              "unknown_future_field": "ignored"
            }
        """.trimIndent()

        val parsed = parseIpLocationResponse(body)!!

        assertTrue(parsed.success)
        assertEquals("Monterrey", parsed.city)
        assertEquals("MX", parsed.countryCode)
        assertEquals("America/Monterrey", parsed.timezone?.id)
        assertEquals("Example Telecom", parsed.connection?.isp)
        assertEquals(25.6866, parsed.latitude!!, 0.0001)
    }

    @Test
    fun parsesProviderFailureWithMessage() {
        val parsed = parseIpLocationResponse("""{"success": false, "message": "quota exceeded"}""")!!
        assertTrue(!parsed.success)
        assertEquals("quota exceeded", parsed.message)
    }

    @Test
    fun parseReturnsNullOnGarbageBody() {
        assertNull(parseIpLocationResponse("<html>gateway error</html>"))
        assertNull(parseIpLocationResponse(""))
    }

    // ── buildLocationResult ────────────────────────────────

    @Test
    fun resultOmitsAbsentFieldsAndNeverExposesTheIpAddress() {
        val parsed = parseIpLocationResponse(
            """{"ip": "203.0.113.7", "success": true, "city": "Monterrey"}""",
        )!!
        val result = Json.parseToJsonElement(buildLocationResult(parsed)).jsonObject

        assertEquals("true", result.getValue("success").jsonPrimitive.content)
        assertEquals("Monterrey", result.getValue("city").jsonPrimitive.content)
        // The raw IP must never reach the model, only the derived location fields.
        assertTrue(result.keys.none { it.equals("ip", ignoreCase = true) })
        assertTrue(!result.containsKey("country"))
        assertTrue(!result.containsKey("isp"))
    }

    // ── formatLocalTimeResult ───────────────────────────────

    @Test
    fun twentyFourHourFlagControlsHourCycle() {
        val now = LocalDateTime.of(2026, 9, 16, 15, 30)
        val zone = ZoneId.of("America/Monterrey")

        val h12 = Json.parseToJsonElement(
            formatLocalTimeResult(now, zone, use24Hour = false, locale = Locale.US),
        ).jsonObject
        val h24 = Json.parseToJsonElement(
            formatLocalTimeResult(now, zone, use24Hour = true, locale = Locale.US),
        ).jsonObject

        assertTrue(h12.getValue("display_datetime").jsonPrimitive.content.contains("3:30 PM"))
        assertTrue(h24.getValue("display_datetime").jsonPrimitive.content.contains("15:30"))
        assertEquals("America/Monterrey", h24.getValue("timezone").jsonPrimitive.content)
        assertEquals("2026-09-16T15:30:00", h24.getValue("iso_datetime").jsonPrimitive.content)
    }

    @Test
    fun dayOfWeekFollowsLocaleNotEnglishAlways() {
        val now = LocalDateTime.of(2026, 9, 16, 8, 0) // Wednesday
        val zone = ZoneId.of("Europe/Madrid")

        val es = Json.parseToJsonElement(
            formatLocalTimeResult(now, zone, use24Hour = true, locale = Locale("es", "MX")),
        ).jsonObject
        val en = Json.parseToJsonElement(
            formatLocalTimeResult(now, zone, use24Hour = true, locale = Locale.US),
        ).jsonObject

        assertEquals("miércoles", es.getValue("day_of_week").jsonPrimitive.content)
        assertEquals("Wednesday", en.getValue("day_of_week").jsonPrimitive.content)
        assertTrue(
            es.getValue("display_datetime").jsonPrimitive.content.contains("miércoles"),
        )
    }

    // ── parseIsoDateTimeToEpochMs (calendar tool) ──────────

    @Test
    fun parsesOffsetQualifiedAndInstantFormsAsAbsolute() {
        val zone = ZoneId.of("America/Monterrey")

        val offsetForm = parseIsoDateTimeToEpochMs("2024-03-15T14:30:00+02:00", zone)
        val instantForm = parseIsoDateTimeToEpochMs("2024-03-15T12:30:00Z", zone)

        // Both denote the same instant, independent of the caller's zone.
        assertEquals(java.time.OffsetDateTime.parse("2024-03-15T12:30:00Z").toInstant().toEpochMilli(), offsetForm)
        assertEquals(offsetForm, instantForm)
    }

    @Test
    fun naiveFormsUseCallerZone() {
        val zone = ZoneId.of("America/Monterrey")

        val tForm = parseIsoDateTimeToEpochMs("2024-03-15T14:30:00", zone)
        val spaceForm = parseIsoDateTimeToEpochMs("2024-03-15 14:30", zone)

        assertEquals(tForm, spaceForm)
        assertEquals(
            LocalDateTime.parse("2024-03-15T14:30:00").atZone(zone).toInstant().toEpochMilli(),
            tForm,
        )
    }

    @Test
    fun dateOnlyYieldsMidnightInCallerZone() {
        val zone = ZoneId.of("America/Monterrey")
        val midnight = LocalDateTime.parse("2024-03-15T00:00:00")
            .atZone(zone).toInstant().toEpochMilli()

        assertEquals(midnight, parseIsoDateTimeToEpochMs("2024-03-15", zone))
    }

    @Test(expected = DateTimeParseException::class)
    fun garbageDateThrowsInsteadOfSucceeding() {
        parseIsoDateTimeToEpochMs("15/03/2024", ZoneId.of("UTC"))
    }

    // ── parseRepeatDays (set_alarm) ───────────────────────

    @Test
    fun repeatDaysAcceptFullNamesAbbreviationsAndNumbers() {
        val byName = parseRepeatDays(listOf("Monday", "friday"))!!
        val byAbbr = parseRepeatDays(listOf("mon", "fri"))!!
        val byNumber = parseRepeatDays(listOf("2", "6"))!!

        assertEquals(java.util.Calendar.MONDAY, byName[0])
        assertEquals(java.util.Calendar.FRIDAY, byName[1])
        assertEquals(byName, byAbbr)
        assertEquals(byName, byNumber)
    }

    @Test
    fun repeatDaysNullPassthroughWhenOmitted() {
        assertNull(parseRepeatDays(null))
    }

    @Test
    fun repeatDaysFailsClosedOnUnknownWeekday() {
        assertNull(parseRepeatDays(listOf("monday", "someday")))
        assertNull(parseRepeatDays(listOf("")))
    }

    // ── resolveEventWindow (list_calendar_events) ─────────

    @Test
    fun eventWindowDefaultsToNowPlusSevenDays() {
        val now = 1_000_000L
        val zone = ZoneId.of("UTC")

        val window = resolveEventWindow(null, null, zone, now)!!

        assertEquals(now, window.first)
        assertEquals(now + 7L * 24 * 60 * 60 * 1000, window.second)
    }

    @Test
    fun eventWindowRejectsEndBeforeStart() {
        val zone = ZoneId.of("UTC")
        assertNull(resolveEventWindow("2024-03-20T10:00:00", "2024-03-20T09:00:00", zone, 0L))
        assertNull(resolveEventWindow("2024-03-20T10:00:00", "2024-03-20T10:00:00", zone, 0L))
    }

    // ── resolveUpdateTimes (update_calendar_event) ───────

    @Test
    fun updateKeepsDurationWhenOnlyStartMoves() {
        val zone = ZoneId.of("UTC")
        val currentStart = 1_000_000L
        val currentEnd = currentStart + 90 * 60 * 1000
        val newStart = currentStart + 60 * 60 * 1000

        val (start, end) = resolveUpdateTimes(currentStart, currentEnd, newStart, null, false, zone)!!

        assertEquals(newStart, start)
        assertEquals(newStart + 90 * 60 * 1000, end)
    }

    @Test
    fun updateRejectsNonAllDayEndBeforeStart() {
        val zone = ZoneId.of("UTC")
        assertNull(resolveUpdateTimes(2_000_000L, 3_000_000L, 2_000_000L, 1_000_000L, false, zone))
    }

    @Test
    fun updateAllDayReanchorsToUtcMidnightSpan() {
        val zone = ZoneId.of("America/Monterrey") // UTC-6
        // 2024-03-15 14:30 local → all-day span anchored at 2024-03-15T00:00Z.
        val startMs = parseIsoDateTimeToEpochMs("2024-03-15T14:30:00", zone)

        val (start, end) = resolveUpdateTimes(startMs, startMs, null, null, true, zone)!!

        assertEquals(parseIsoDateTimeToEpochMs("2024-03-15T00:00:00", ZoneId.of("UTC")), start)
        assertEquals(start + 24L * 60 * 60 * 1000, end)
    }

    // ── buildCalendarEventsResult ─────────────────────────

    @Test
    fun allDayEventsRenderAsBareDatesAndTimedAsIsoDatetimes() {
        val zone = ZoneId.of("America/Monterrey")
        val events = listOf(
            CalendarEventProjection(
                id = 42L,
                title = "Trip",
                startMs = parseIsoDateTimeToEpochMs("2024-03-15", zone),
                endMs = parseIsoDateTimeToEpochMs("2024-03-15", zone) + 24L * 60 * 60 * 1000,
                allDay = true,
                location = null,
                description = null,
            ),
            CalendarEventProjection(
                id = 43L,
                title = "Dentist",
                startMs = parseIsoDateTimeToEpochMs("2024-03-16T09:00:00", zone),
                endMs = parseIsoDateTimeToEpochMs("2024-03-16T09:30:00", zone),
                allDay = false,
                location = "Clinic",
                description = null,
            ),
        )

        val result = Json.parseToJsonElement(
            buildCalendarEventsResult(events, zone, limit = 25),
        ).jsonObject
        val arr = result.getValue("events").jsonArray

        assertEquals("2", result.getValue("count").jsonPrimitive.content)
        val allDay = arr[0].jsonObject
        assertEquals("2024-03-15", allDay.getValue("date").jsonPrimitive.content)
        assertTrue(!allDay.containsKey("start"))
        val timed = arr[1].jsonObject
        assertEquals("2024-03-16T09:00:00", timed.getValue("start").jsonPrimitive.content)
        assertEquals("Clinic", timed.getValue("location").jsonPrimitive.content)
        assertTrue(!result.containsKey("truncated"))
    }

    @Test
    fun resultFlagsTruncationAtLimit() {
        val zone = ZoneId.of("UTC")
        val event = CalendarEventProjection(1L, "A", 0L, 0L, false, null, null)

        val result = Json.parseToJsonElement(
            buildCalendarEventsResult(listOf(event, event), zone, limit = 2),
        ).jsonObject

        assertTrue(result.containsKey("truncated"))
    }

    // ── anchorAllDayStartUtc ───────────────────────────────

    @Test
    fun anchorUsesUtcMidnightOfTheLocalDate() {
        val zone = ZoneId.of("America/Monterrey") // UTC-6
        val localMorning = parseIsoDateTimeToEpochMs("2024-03-15T08:00:00", zone)

        val anchored = anchorAllDayStartUtc(localMorning, zone)

        assertEquals(parseIsoDateTimeToEpochMs("2024-03-15T00:00:00", ZoneId.of("UTC")), anchored)
    }

    // ── buildAssistantActionSummary (staged actions) ───────

    private fun args(vararg pairs: Pair<String, String>): Map<String, kotlinx.serialization.json.JsonElement> =
        pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }

    @Test
    fun alarmSummaryNamesClockTimeAndLabel() {
        val summary = buildAssistantActionSummary(
            "set_alarm",
            args("hour" to "7", "minutes" to "30", "label" to "Work"),
        )!!
        assertTrue(summary.contains("07:30"))
        assertTrue(summary.contains("Work"))
        assertTrue(!summary.contains("repeats"))
    }

    @Test
    fun timerSummaryHumanizesDuration() {
        val summary = buildAssistantActionSummary(
            "set_alarm",
            args("duration_seconds" to "600", "label" to "Tea"),
        )!!
        assertTrue(summary.contains("10 min"))
        assertTrue(summary.contains("Tea"))
    }

    @Test
    fun incompleteArgumentsFailClosedInsteadOfQueueing() {
        assertNull(buildAssistantActionSummary("set_alarm", args()))
        assertNull(buildAssistantActionSummary("set_alarm", args("hour" to "7")))
        assertNull(buildAssistantActionSummary("create_calendar_event", args("start_time" to "2024-03-15")))
        assertNull(buildAssistantActionSummary("update_calendar_event", args("event_id" to "5")))
        assertNull(buildAssistantActionSummary("delete_calendar_event", args()))
        assertNull(buildAssistantActionSummary("get_local_time", args()))
    }

    @Test
    fun updateSummaryListsOnlyProvidedChanges() {
        val summary = buildAssistantActionSummary(
            "update_calendar_event",
            args("event_id" to "42", "title" to "New", "location" to "Office"),
        )!!
        assertTrue(summary.contains("42"))
        assertTrue(summary.contains("title → \"New\""))
        assertTrue(summary.contains("location → Office"))
        assertTrue(!summary.contains("start"))
    }

    // ── parseAssistantActionResult ─────────────────────────

    @Test
    fun successResultMapsToSucceededWithoutError() {
        val (ok, error) = parseAssistantActionResult("""{"success": true}""")
        assertTrue(ok)
        assertNull(error)
    }

    @Test
    fun errorResultKeepsCodeAndMessage() {
        val (ok, error) = parseAssistantActionResult(
            """{"success": false, "error": "permission_denied", "message": "Calendar permission not granted"}""",
        )
        assertTrue(!ok)
        assertEquals("permission_denied: Calendar permission not granted", error)
    }

    @Test
    fun garbageResultFailsClosed() {
        val (ok, error) = parseAssistantActionResult("not json")
        assertTrue(!ok)
        assertTrue(!error.isNullOrEmpty())
    }
}
