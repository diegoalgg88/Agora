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
(cap 3). Pending lists arrive as parameters; the scheduler owns the
snapshot/remove lifecycle. Pinned by `HeartbeatPromptBuilderTest`.

## 8. Manual run ("Run now")

`runHeartbeatNow()` (Settings → Automation) bypasses the due-gate BY DESIGN and
also works while heartbeat is disabled: an explicit user action in Settings is
consent, and it is the supported way to test the prompt without enabling the
daemon. It shares the atomic in-flight guard with the loop. This bypass is an
intentional recorded decision (2026-09-23), not an accident.

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

## 11. Test map

| Focus | Suite |
|---|---|
| Due-gate boundaries incl. midnight wrap | `HeartbeatDueGateTest` |
| Prompt sections, caps, sort order | `HeartbeatPromptBuilderTest` |
| Previous-results extraction (tool rows excluded, labels, cap, empty) | `HeartbeatRecentResponsesTest` |
| Busy/Failure/Success outcome semantics | `HeartbeatSchedulerBusyPathTest` |
| Heartbeat prompt badge/collapse rendering decision | `HeartbeatPromptRenderingTest` |
| requestKind persistence at the accepted-input boundary | `AcceptedInputGraphWriterTest` |
| Room v36→v37 runs.requestKind migration | `Migration36To37Test` |
| Log recording bounds | `HeartbeatManagerTest` |
| Conversation resolution/migration | `HeartbeatConversationRepositoryTest` |
