# Automation (Heartbeat, SMS, Email polling) Contract

Authoritative contract for Agora's daemon-driven heartbeat: due-gating, prompt
assembly, snapshot consumption, Busy semantics, manual runs, and logging.
Implementation: `automation/HeartbeatScheduler.kt`, `data/HeartbeatManager.kt`,
`data/HeartbeatPromptBuilder.kt`, `data/repository/HeartbeatConversation.kt`,
`daemon/DaemonController.kt`, `service/HeartbeatNotifier.kt`,
`data/local/ChatContextCompactDao.kt` (`getRecentFinalModelResponses`),
`ui/settings/HeartbeatRunStatusItems.kt`.

Task/Loop scheduling via AlarmManager/WorkManager is owned by
`automation/TaskManager`/`LoopManager` and `AutomationScheduler`; this contract
covers the heartbeat loop that rides the daemon's 60-second cycle.

## 1. Lifecycle and state ownership

| State | Owner | Notes |
|---|---|---|
| Daemon enabled flag | DataStore | `DaemonController` leases the shared `AgoraForegroundService` (no second service) and starts/stops the scheduler. POST_NOTIFICATIONS denial on Android 13+ reverts the setting (fail-closed). |
| Heartbeat config (enabled, interval, active hours, prompt, model override, conversation id, watermark) | DataStore via `SettingsRepository` | `heartbeatEnabled` defaults to **false** (opt-in): fresh installs must not consume tokens when the daemon is enabled. Existing installs keep their stored value. |
| Heartbeat run log | Room `heartbeat_logs` | Max 5 newest rows; error text capped at 300 chars (`HeartbeatManager.MAX_LOGGED_ERROR_CHARS`). |
| 60-second loop | `HeartbeatScheduler` coroutine in the scheduler's own scope | Not AlarmManager; dies with the process, restarted by `DaemonController`. |

## 2. Due-gate (pure policy)

`HeartbeatManager.isHeartbeatDue(nowMs)` — `nowMs` injectable; the hour-window
check is the pure `isHourInActiveWindow(hour, start, end)`:

- Never due when disabled.
- Due when `now - lastHeartbeatEpochMs >= intervalMinutes` AND current hour is
  inside the active-hours window.
- Window semantics: `start <= end` is same-day, end-exclusive (`start == end`
  is an empty window — never due); `start > end` wraps midnight (e.g. 22→8).
- Boundary tests are in `HeartbeatDueGateTest`.

## 3. The 60-second cycle

Per tick (inside try/catch, one-time conversation-setting migration on first
pass): run heartbeat if due → poll SMS if enabled and per-interval backoff
elapsed → poll emails per account if due (composite per-account gates; see
email.md) → notifications are push-driven, never polled.

## 4. One heartbeat run

1. **Conversation resolution**: user-pinned `heartbeatConversationId`, else the
   single `origin="heartbeat"` conversation (created on first use).
   `heartbeatModel` always applies as an override when set; null inherits the
   conversation's model.
2. **Snapshot then prompt**: pending SMS/notifications/emails are captured
   BEFORE the call; the prompt builder is pure and receives them as parameters.
   Previous results come from the bounded tail query (section 6).
3. **Generation**: `TaskExecutionEngine.runOnce(..., requestKind="heartbeat")` —
   the same pipeline as foreground generation; no parallel path. Overlap of the
   loop tick and a manual run is prevented by an `AtomicBoolean`
   compare-and-set in-flight guard.
4. **Outcome handling** (section 5), watermark update, Room log row, failure
   notification only when backgrounded.

## 5. Outcome semantics

| Outcome | Watermark (`lastHeartbeatEpochMs`) | Log row | Push notification | Pending queues |
|---|---|---|---|---|
| `Success` | advanced | success row | no | exactly the captured snapshot consumed; per-account email `lastSeenUid` advances past the delivered batch so `check_email` never repeats it |
| `Failure` | advanced (avoids hammering a failing provider every 60s) | failure row (reason, capped) | only if app backgrounded | NOT consumed — items survive to the next heartbeat |
| `Busy` | **not advanced** | **no row** | **never** | untouched — the 60s loop retries naturally; "Recent runs" never fills with busy noise |

"Push notification: no" for Success covers the generic "Response ready" terminal
notification too: the heartbeat run passes `suppressTerminalNotification = true`
(`GenerationCallbacks` -> `GenerationCompletionEffectsExecutor`), which suppresses ONLY
that notification (unread marking and every other terminal effect are unchanged).
Before this, every clean check posted "Agora responded: HEARTBEAT_OK" while backgrounded.

**Consume-on-success only** is the invariant: exactly the snapshot the AI saw is
removed, and only on success. Items arriving during the call were never in the
snapshot and survive.

## 6. Previous results (bounded tail)

- The prompt's `## Previous Heartbeat Results` section is fed EXCLUSIVELY by
  `getRecentFinalModelResponses(conversationId, limit = 3)` (newest first).
  Never load the conversation's message graph for this — the heartbeat
  conversation grows without bound (load-performance contract).
- Final-response rows are MODEL participant, `text != ''`, `toolCallJson IS
  NULL` (tool-assembly rows persist as blank MODEL rows), `status != 'ERROR'`
  (provider failure text is not a heartbeat result).
- Index 0 renders under the "Most recent" label; the input list must be
  newest-first. Behavior pinned by `HeartbeatRecentResponsesTest`.

## 7. Prompt structure (pure builder)

Sections, in order, each omitted when empty: base prompt (custom user
instructions or the default `DEFAULT_HEARTBEAT_PROMPT` — which forbids the model
from rescheduling heartbeat), tasks & loops (due enabled tasks cap 10, active
loops cap 5), memory promotion candidates, new SMS (cap 20), new notifications
(cap 20, newest first), new emails (cap 20, newest first), previous results
(cap 3), and — only when SMS/notifications/emails are present — a final `## Response Rule`
(never answer HEARTBEAT_OK when an incoming item is time-sensitive or needs action; the
consume-on-success invariant means a wrong HEARTBEAT_OK loses the item). Tasks & loops also
lists scheduled-but-not-due tasks (cap 10, with next run time) and is never emitted as a bare
header; loops are read even when there are no tasks. Pending lists arrive as parameters; the scheduler owns the
snapshot/remove lifecycle. Pinned by `HeartbeatPromptBuilderTest`.

## 8. Manual run ("Run now")

`runHeartbeatNow()` (Settings → Automation) bypasses the due-gate BY DESIGN and
also works while heartbeat is disabled: an explicit user action in Settings is
consent, and it is the supported way to test the prompt without enabling the
daemon. It shares the atomic in-flight guard with the loop. This bypass is an
intentional recorded decision (2026-09-23), not an accident.

**Scope owner (2026-10-03 device-verified fix):** the manual run MUST be launched
in the scheduler's own process scope (`runHeartbeatNowAsync()`), never in a
composition scope. Launching it via `rememberCoroutineScope()` from the Settings
page cancelled the in-flight run the moment the user navigated to the chat to
watch it: the CancellationException finalized the Run as STOPPED/USER_STOPPED
with no heartbeat log row — presented as a mysterious "Generation stopped".

## 9. Tooling during a heartbeat run

`TaskExecutionEngine` is constructed with extra tool providers for headless
callers: `promote_learning` (heartbeat), SMS, notification, assistant-device,
and email tools — so the model can act on what the prompt shows without
changing the Task/Loop default tool set.

## 10. Heartbeat prompt rendering (badge/collapse)

The heartbeat prompt is persisted as a USER message of a Run whose `requestKind` is
recorded at the single durable boundary both interactive and headless sends share
(`AcceptedInputGraphWriter.commit()`; Room v37, `runs.requestKind` nullable — pre-v37
runs are null and render unchanged). UI rule: a USER message whose Run's
`requestKind == "heartbeat"` renders as a collapsed badge row (`HeartbeatPromptBadgeRow`,
label `heartbeat_prompt_badge`) with a one-line preview; tap expands the full prompt,
tap again collapses. Expand state is ephemeral UI state — never persisted; default
collapsed. It is the same message with a different presentation (no dual rendering).
Other kinds (task, loop, compact, ...) may adopt badges later without schema changes —
the column is generic; only `heartbeat` renders specially today. The projection is
conversation-scoped via the existing `getRunsForConversation` flow (no new query).

## 11. Rich task confirmations (`task_confirmations`, Room v38+)

Opt-in (Settings → Automation → Task confirmations, DataStore
`taskConfirmationEnabled`, default **false**, exported in `PortableSettingsArchive`).
With the toggle OFF the heartbeat's pre-feature behaviour is byte-identical.

### Durable owner and identity
`task_confirmations` is the sole durable owner of a rich confirmation
(`data/local/TaskConfirmationEntity.kt`, migration `MIGRATION_37_38` — CREATE-only,
no CHECK constraints: Room ≥2.7 validates them post-migration and the closed-enum
invariant lives in the typed enums). DAO in `ChatHeartbeatSmsDao`; store is
`data/TaskConfirmationStore.kt` — deliberately separate from `AssistantActionStore`
(that one is owned by the device-tools contract and dispatches device effects;
this one only persists and consumes). Dedup identity is
`(sourceType, conversationId, modelMessageId)` (unique index): `modelMessageId`
because `TaskExecutionEngine.Result.Success` exposes only `(modelMessageId, text)` —
the internal `runId` never leaves the engine, and `modelMessageId` is unique per
generation. Rows are ephemeral: cap 20 PENDING rows (oldest evicted in the same staging
transaction; resolved rows are dedup tombstones outside the cap so they can never evict
an unresolved row), 7-day cleanup run inside every staging transaction (so it does not depend on the heartbeat/daemon being on) and again on the heartbeat tick's retention sweep.

### Actionable definition (the filter lives in the scheduler)
A success result stages a confirmation only when, after `trim()`, it is non-empty
AND not exactly `HeartbeatManager.HEARTBEAT_OK_SENTINEL` (case-insensitive, ignoring
markup/punctuation decoration such as `**HEARTBEAT_OK**` or `HEARTBEAT_OK.`) — the
value the prompt asks the model to emit when nothing needs attention; staging it
would invert the feature's purpose. A result that STARTS with the sentinel followed
by substantive content gets the prefix and its immediate separator stripped; a word
that merely begins with the same letters (`HEARTBEAT_OKAY…`) is content. A body that
collapses to blank after the plain-text projection is silent. The
store never sees sentinels; `extractActionableHeartbeatText` (internal,
`HeartbeatScheduler`) is the single decision point. Staging runs strictly AFTER the
snapshot consume-on-success and retention sweeps, and is best-effort: a staging/posting
failure is logged and never skips the watermark advance or the run log (otherwise the
60 s loop would re-run an already-completed heartbeat).

### Surfaces and consume-on-resolve
Notification (`service/TaskPromptNotifier`, channel `task_confirmation_v1`,
IMPORTANCE_HIGH; actions Confirm + Snooze (fixed 10 min) + Open conversation; DeleteIntent == Dismiss;
IDs on base 51000+ (20-bit hash space) derived from the confirmation ID so re-posts
replace, never stack; the localized header comes from the single
`TaskPromptNotifier.titleResFor` shared by notification, banner and card), the bottom-anchored translucent card (`ui/automation/TaskConfirmationActivity`,
theme derives from `Theme.AssistantOverlay`), and the chat banner
(`PendingTaskConfirmationsBanner`, store read straight from the container — collected
only while the chat composes). Resolutions are single-winner Room transitions
(`PENDING → ACKNOWLEDGED/DISMISSED` with `status='PENDING'` predicate); the losing
surface no-ops. Every consuming surface cancels the notification unconditionally, even
when it lost the race, so no dead action buttons linger.
If the platform refuses the post (`canPost()` false, permission revoked), the row
survives for the banner — no orphan result.

### Snooze contract (fetch-and-clear)
`TaskConfirmationStore.consumeDueReminders(now)` is the ONLY writer of
`remindAtEpochMs = NULL`. It returns due rows and clears the column in one
transaction; rows resolved by another surface between SELECT and UPDATE are
dropped (the clear UPDATE carries the PENDING predicate and reports 0). The
scheduler's tick calls it every 60 s — even when the heartbeat itself is not due —
and re-posts only when backgrounded. With the daemon off there is no tick, but the snooze still comes back: the row stays PENDING, the banner re-shows it at its deadline, and the notification is re-posted by a one-shot inexact alarm (`TaskPromptNotifier.scheduleReminder`, fired into `TaskConfirmationReceiver.ACTION_REMIND`, re-armed by `BootReceiver`; the tick is only a backstop and both go through `postDueReminders`) (snooze is a
convenience, not a contract). While snoozed, the banner hides the row and un-hides it on its own clock (`PendingTaskConfirmationsBanner`), not on the daemon tick; staging also never re-posts a re-staged row that is no longer PENDING.

### TASK result surfacing (F8)
Scheduled tasks share the heartbeat's confirmation pipeline. After a successful
`TaskManager` execution (both the WorkManager path and the recovery path), the
`surfaceTaskResult` hook fires — always actionable (the user explicitly asked for
the task; no sentinel filter), with the shared plain-text projection, isolated in
its own try/catch so a confirmation failure can never flip a completed run into a
failure (same isolation as the title update; both paths share `surfaceResultIsolated`, and the recovery path is idempotent through the dedup key). The durable row title is the task's
own name; `TaskPromptNotifier.displayTitleFor(sourceType, rowTitle)` renders
HEARTBEAT's localized header but surfaces TASK/LOOP row titles directly (the name
identifies the origin better than any generic string; blank falls back to the
settings label). The global toggle/mode/style settings apply unchanged — a daily
weather report reads best in AUTO ("informative only") mode, which the user picks
once in Settings. `stageAndNotifyTaskConfirmation` (`automation/TaskConfirmationSurfacing.kt`, shared with the heartbeat) owns the PROMPT/AUTO
branching for non-heartbeat sources. LOOP policy: a loop fires every few minutes, so only the FINAL cycle's successful result is surfaced (`LoopManager.surfaceFinalLoopResult`, gated on `claimed.active == false`, i.e. exactly once per run; a user Stop or a failed cycle surfaces nothing), titled with the loop conversation's own title. Per-cycle surfacing is deliberately not offered: it floods the banner and the shade.

### Presentation modes and card style (F7, Universal Installer analogue)
`Settings → Automation → Task confirmations` exposes two presentation modes
(`taskConfirmationMode`, DataStore, portable): **PROMPT** (default; durable row +
banner + actionable notification, all §11 semantics above) and **AUTO** — the
Universal Installer `AutoNotification` analogue: an informational auto-dismiss
notification ONLY. AUTO stages NOTHING: no durable row, no banner, no actions, no
delete intent — a result nobody is asked to confirm must not occupy durable state
(`TaskPromptNotifier.postInfo` on its own DEFAULT-importance channel `task_confirmation_info_v1`, so informational posts neither heads-up nor share mute settings with prompts; ID derived from source+conversation+message so
consecutive results replace each other). The HEARTBEAT_OK actionability filter and
the foreground suppression apply to both modes. Card anchor
(`taskConfirmationCardStyle`, portable): BOTTOM (sheet) or CENTERED (dialog) — same
card content, only the anchor/max-width changes (`TaskConfirmationCard(centered=)`).
Settings lives in `SettingsTaskConfirmations` (sub-object pattern, 999-line budget)
with radios + live mini-preview mirroring Universal Installer's "Pantalla de
instalación" preview.

### Deliberate exclusion from `.agora`
`task_confirmations` is NOT serialized by `DataExporter` and NOT restored by
`DataImporter`: the rows are ephemeral and reference conversations a REPLACE restore
may not include; restoring them would create orphans with zero user value. This
exclusion is deliberate — do not "fix" it by adding export without the
email-style reconciliation.

### Failure path unchanged
The pre-feature failure semantics (`!foreground && !success` → plain
`HeartbeatNotifier` on channel `heartbeat_notifications`) are byte-identical. An
error is not a reminder: it is never staged, confirmed, or snoozed. Toggle OFF with
pre-existing PENDING rows: the rows stay visible/resolvable in the banner, no new
rows are staged, and the 7-day cleanup ages them out if never resolved — expected
behaviour, not a bug.

## 12. Test map

| Focus | Suite |
|---|---|
| Due-gate boundaries incl. midnight wrap | `HeartbeatDueGateTest` |
| Prompt sections, caps, sort order | `HeartbeatPromptBuilderTest` |
| Previous-results extraction (tool rows excluded, labels, cap, empty) | `HeartbeatRecentResponsesTest` |
| Busy/Failure/Success outcome semantics | `HeartbeatSchedulerBusyPathTest` (extended: toggle ON/OFF staging, HEARTBEAT_OK silence, foreground-no-post, failure-never-staged) |
| Heartbeat prompt badge/collapse rendering decision | `HeartbeatPromptRenderingTest` |
| requestKind persistence at the accepted-input boundary | `AcceptedInputGraphWriterTest` |
| Room v36→v37 runs.requestKind migration | `Migration36To37Test` |
| Log recording bounds | `HeartbeatManagerTest` |
| Conversation resolution/migration | `HeartbeatConversationRepositoryTest` |
| Rich confirmation store contract (dedup, cap, single-winner, snooze fetch-and-clear, race drop) | `TaskConfirmationStoreTest` |
| Actionable-text filter (sentinel silence, prefix strip, pass-through) | `HeartbeatActionableTextTest` |
| Confirmation notification-ID stability/range (base 51000+, production function) | `TaskPromptNotifierIdTest` |
