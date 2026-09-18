# Assistant Device Tools — Module Contract

**Scope:** the ten single-shot device-action tools owned by `AssistantDeviceToolProvider`
(`app/src/main/java/com/newoether/agora/tool/AssistantDeviceToolProvider.kt`): `set_alarm`,
`open_file`, `create_calendar_event`, `list_calendar_events`, `update_calendar_event`,
`delete_calendar_event`, `get_location_from_ip`, `get_local_time`, `open_url`,
`send_notification`.

**Reference implementation:** Kai (`composeApp/.../tools/SetAlarmTool.kt`, `OpenFileTool.kt`,
`CreateCalendarEventTool.kt`/`CalendarRepository.kt`, `SendNotificationTool.kt`,
`CommonTools.kt`). Behavior below was confirmed against that code. The calendar
read/update/delete tools go beyond Kai parity (owner decision 2026-09-17: the
already-granted `READ_CALENDAR`+`WRITE_CALENDAR` permissions cover the full
`CalendarContract` CRUD surface, so the create-only limitation was artificial).

## Category definition

These tools produce **one tangible, visible action on the device, on the user's behalf, per
invocation**. They are distinct from:

- **AI Tools** (web search, memory, RAG, MCP, skills, image generation): knowledge/reasoning
  support with no direct device-visible side effect.
- **System Tools** (shell, SMS, notifications, heartbeat, automation): OS-level capabilities
  that feed or execute the AI's ongoing operation, not a single user-requested action.

## Invariants

1. **Opt-in per tool.** Each tool has its own DataStore toggle
   (`SettingsPreferenceSchema.ASSISTANT_*`), default **off**, exposed as a `Switch` in
   Settings → Tools → "Assistant Tools". `definitions()` returns nothing for a disabled
   tool, so the model never sees it (same gating pattern as `SmsToolProvider`).
2. **One-shot execution only.** No streaming, no progress events, no persistent state.
   `execute()` returns exactly one JSON result (`success` + payload, or
   `success=false` + `error` code + human-readable `message`).
3. **Permissions are minimal and runtime-gated.**
   - `set_alarm`, `get_local_time`, `get_location_from_ip`, `open_url`, `open_file`:
     no permissions (implicit intents / system clock / IP geolocation via `ipwho.is`).
   - `send_notification`: relies on the existing `POST_NOTIFICATIONS` permission; fails
     with `notifications_disabled` when the user revoked it.
   - `create_calendar_event`, `list_calendar_events`, `update_calendar_event`,
     `delete_calendar_event`: all four require `READ_CALENDAR` + `WRITE_CALENDAR`,
     requested from the Settings toggle via
     `ActivityResultContracts.RequestMultiplePermissions`; the toggle only stays on when
     both are granted. One launcher serves all four rows (the pending setter records which
     toggle triggered the request). At execution time each tool re-checks and fails with
     `permission_denied` otherwise.
4. **`set_alarm` is silent** (`EXTRA_SKIP_UI=true`, owner decision 2026-09-13, Kai parity).
   It delegates to the device clock app via `AlarmClock.ACTION_SET_ALARM` /
   `ACTION_SET_TIMER`; `SCHEDULE_EXACT_ALARM` is NOT used (that belongs to Automation).
   **Clock-time precedence** (owner decision 2026-09-17): when `hour`+`minutes` are
   present, an alarm is created even if `duration_seconds` was also supplied — a named
   clock time must never be downgraded to a countdown. `duration_seconds` only creates a
   timer when no hour/minutes were given. The tool description instructs the model
   accordingly (models otherwise tend to convert "wake me at 7:30" into durations).
   Optional `days` (`EXTRA_DAYS`) creates a repeating alarm; weekday names and
   abbreviations are validated by the pure `parseRepeatDays`, which fails closed (no
   alarm) on any unknown value so a partially-understood repeat spec never produces a
   wrong schedule. Android exposes no API to list/modify existing clock-app alarms, so
   set-only is a platform limit, not an implementation gap.
5. **`open_file` is sandbox-scoped.** Paths are relative to the sandbox home
   (`SandboxManager.getSandboxHomeDir()`); absolute paths and `..` segments are rejected.
   Files open via the existing `FileProvider` (`sandbox-home` path in `file_paths.xml`)
   with `ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION`. On the Play flavor (no sandbox)
   the tool is hidden entirely.
6. **`open_url` never returns page content.** It only opens the link (`ACTION_VIEW`,
   http/https only). Reading content is `web_fetch`'s job; the tool description says so.
7. **`create_calendar_event` writes directly** via `CalendarContract` (owner decision
   2026-09-13: verifiable `event_id` + programmatic reminder, Kai parity) instead of an
   `ACTION_INSERT` intent. Date parsing accepts offset-qualified ISO-8601 (converted to
   instants) and naive values (interpreted in the device zone); end defaults to start + 1h,
   reminder defaults to 15 min.
8. **No new concept per tool.** All ten share the one provider; a new tool in this
   category is a new branch in `definitions`/`execute` plus one DataStore key, not a new
   class (AGENTS.md §4.5).
9. **`get_location_from_ip` fails with distinct error codes** (owner decision 2026-09-16):
   non-2xx statuses map to `rate_limited` (429), `service_error` (5xx) or `no_response`
   (other), via `locationHttpError` — so the model can tell a retryable rate limit from an
   outage. The raw IP is never echoed back to the model; only derived fields (city, region,
   country, coordinates, timezone, ISP) are. The Settings copy must disclose the
   third-party service (`ipwho.is`) and the city-level approximation.
10. **`get_local_time` is locale- and hour-cycle-aware** (owner decision 2026-09-16): the
    display string follows the system 12/24-hour setting, and day-of-week/month names
    follow the device locale. The projection is the pure `formatLocalTimeResult`
    (no Android deps, unit-tested); `execute()` only supplies clock, zone, flag, locale.
11. **`set_alarm` holds `com.android.alarm.permission.SET_ALARM`** (owner decision
    2026-09-16): the manifest declares the normal install-time permission
    `ACTION_SET_ALARM`/`ACTION_SET_TIMER` require; a `SecurityException` still fails
    closed as `alarm_permission_missing`. Hour/minutes outside 0-23/0-59 are rejected as
    `invalid_arguments` before the intent is built (defense against model arithmetic).
12. **`send_notification` IDs are offset above every other notification ID in the app**
    (base 10 000, owner decision 2026-09-16) so an assistant notification can never
    replace the foreground service (1), heartbeat/auto-backup (1001) or live-voice (424)
    notifications, and vice versa.
13. **`create_calendar_event` validates and anchors all-day events correctly** (owner
    decision 2026-09-16): `end_time` <= `start_time` fails as `end_before_start` (except
    all-day); all-day events are re-anchored to UTC midnight of the named local date with
    a 24 h span and `EVENT_TIMEZONE=UTC` (CalendarContract convention); date-only
    ISO input (`2024-03-15`) is accepted and means midnight in the device zone. A failed
    reminder insert is surfaced as a `warning` field on the success result, never
    silently dropped.
14. **Calendar CRUD is full-lifecycle, still one provider** (owner decision 2026-09-17):
    `list_calendar_events` reads `CalendarContract.Instances` (recurring events expanded
    per instance) with a window defaulting to now → +7 days, capped at 50 (default 25,
    `truncated` flag when the cap is hit); `update_calendar_event` re-reads the event's
    current row, applies only the provided fields (pure `resolveUpdateTimes` keeps the
    existing duration when only the start moves and re-anchors all-day spans to UTC
    midnight), and fails `not_found`/`end_before_start` before writing;
    `delete_calendar_event` deletes by `event_id` and fails `not_found` on 0 rows. All
    three reuse `parseIsoDateTimeToEpochMs` and the shared calendar permission check;
    each has its own toggle (invariant 1). JSON projection is the pure
    `buildCalendarEventsResult` (all-day events render as bare local dates, timed events
    as zone-local ISO datetimes — unit-tested, no Android deps).
15. **Destructive actions are staged for user approval** (owner decision 2026-09-17,
    mirrors the SMS draft flow): `set_alarm`, `create_calendar_event`,
    `update_calendar_event` and `delete_calendar_event` never execute from the AI path.
    The provider stages the request into `assistant_actions` (Room v35, cap 20, 7-day
    cleanup — same shape as `sms_drafts`) and returns `staged=true` + the review summary;
    the tool descriptions tell the model to ask the user to review. Execution happens
    only from the user's tap in the chat banner (`PendingAssistantActionsBanner` →
    `AssistantActionStore.approveAction` → `runApprovedAction`), which runs the exact
    same dispatch and validations. Staging fails closed (`invalid_arguments`) when the
    pure `buildAssistantActionSummary` cannot build a reviewable one-line summary.
    A successful approval removes the row; failure records the error for Retry/Discard.
    Read-only tools (`list_calendar_events`, `get_local_time`, etc.) stay direct.

## Settings UX

- Grouped under **Assistant Tools** (`settings_tools_group_assistant`) in
  `SettingsToolsPage.kt`; rows are plain `SettingsItem` + `Switch` (no disclosure glyphs —
  `development/settings-ui-ux.md`).
- **No duplicated toggles (owner rule, 2026-09-13):** a tool's enable/disable control lives
  in that tool's own Settings page when one exists (web search, conversation search,
  skills, memory, image generation, shell, MCP, automation all have one). The Tools screen
  must never re-host their toggles — it exists only for tools that have no dedicated page
  (today: exactly this category). If an assistant tool later gains real parameters, first
  look for an existing page that owns that concern; only create a new subpage (pattern:
  `SettingsShellPage`) when no owner exists.
- All labels/descriptions live in `res/values*/tools_strings.xml` and must exist in every
  supported locale (`development/application-ui.md` §3).

## Portability

The ten toggles are exported and imported by `PortableSettingsArchive`
(`assistant*Enabled` keys) and cleared by `SettingsManager`'s full reset.

## Known limitations

- `get_location_from_ip` accuracy depends on the IP geolocation provider (`ipwho.is`) and
  reflects the network egress point, not GPS.
- `web_fetch` SSRF hardening (private/loopback host blocking, per-redirect validation in
  `WebSearchToolProvider.fetchWithSsrfGuard`) covers literal IP hosts and obvious local
  names; it does not resolve DNS, so a public hostname that resolves to a private address
  (DNS rebinding) is out of scope.
- **Alarm modify/delete is rejected, not pending** (owner decision 2026-09-17): the public
  API only sets alarms. A best-effort `dismiss_alarm` (label/time search via
  `ACTION_DISMISS_ALARM`, unsupported by many OEM clock apps) and an own AlarmManager
  engine (full CRUD but outside the clock app, `SCHEDULE_EXACT_ALARM`, battery exemption)
  were both evaluated and declined — no assistant (Kai included) modifies clock-app
  alarms. Do not re-propose these; `set_alarm` stays set-only.
