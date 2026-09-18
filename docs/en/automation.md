# Automation

Agora provides saved **Tasks**, conversation-scoped **Loops**, and a proactive **Heartbeat**. Open **Settings → Tools → Automation** to control model access and Android background execution.

## Tasks

Open **Tasks** from the app navigation to create and manage saved prompts. A Task contains a name and prompt, an optional model override, and a manual, one-time, daily, weekly, monthly, yearly, or custom five-field cron schedule.

Use **Run now** to execute a Task without waiting for its schedule. Each execution creates history with its status and generated conversation. Disabling scheduled execution keeps the Task available for manual runs. A one-time schedule must be in the future.

## Conversation Loops

A Loop belongs to one conversation and starts another generation after a configured interval. It may inject a prompt on each cycle and has a maximum-cycle safety limit. Only one active Loop can belong to a conversation.

## Heartbeat

The Heartbeat runs a proactive check-in on a schedule: it collects what changed since the last run — pending Tasks and Loops, unread SMS, recent notifications, and the previous heartbeat result — and generates one summary message in its conversation.

Configure it under **Settings → Tools → Automation → Heartbeat**:

- **Enable** turns the heartbeat on. It runs only while the app process is alive; enable **Daemon mode** (below) to keep it running in the background.
- **Interval** — how often the heartbeat runs (5 minutes to 4 hours).
- **Active hours** — the heartbeat stays quiet outside this window (for example 08:00–22:00; overnight windows that wrap midnight are supported).
- **Conversation** — pin the heartbeat to an existing conversation, or use **Auto (dedicated)** to let Agora maintain a dedicated one.
- **Model override** — run the heartbeat with a specific model regardless of the conversation's model.
- **Custom prompt** — replace the default heartbeat prompt.
- **Recent runs** — the last five outcomes with timestamps, and a **Run now** button for an immediate check-in.

!!! note
    Heartbeat failure logs record only the error text, never message content. If the app is in the background and a run fails, Agora posts a notification about it.

## SMS

On the F-Droid build, Agora can read and send text messages so the heartbeat and the model can work with them. Configure under **Settings → Tools → Automation → SMS**:

- **Read SMS** — new incoming messages are polled into a pending queue that the heartbeat consumes.
- **Send SMS** — allows the model to send a reply. Like other destructive assistant actions, an outgoing SMS is staged first and only sends when you approve it in the chat banner.
- **Poll interval** — how often new messages are fetched (never, 5, 15, 30, or 60 minutes).

Both toggles request their Android SMS permissions when enabled. The queue view shows the pending count, the last poll status, and a manual refresh action.

## Notifications

On the F-Droid build, Agora can listen to notifications from apps you choose, and feed them to the heartbeat. Configure under **Settings → Tools → Automation → Notifications**:

- **Enable** turns the listener on and opens the system notification-access settings.
- **Manage apps** — pick the whitelist of apps whose notifications are captured; everything else is ignored.
- The queue view shows the captured count and a **Clear queue** action.

Agora blocks its own notifications and system UI notifications, and keeps at most the most recent 5000 records.

## Tools

**Access Tasks and Loops** is disabled by default. When enabled, the model can create, list, and delete Tasks and can start or stop the current conversation's Loop. The permission is checked again when a tool executes, so turning it off blocks a previously proposed call from changing automation state.

!!! warning
    Enable automation access only when you want the model to change persistent schedules. A scheduled prompt can call providers and enabled tools in the background.

## Background Execution

**Daemon mode** keeps the app process alive so the heartbeat and SMS polling run on schedule. It shows as an ongoing notification and requests Android's notification permission on Android 13 and newer. It is off by default.

Agora normally uses battery-friendly inexact alarms, so Android may delay a run.

**Exact Execution** requests exact alarms for Tasks and Loops. On Android 12 and newer, enabling it opens the system **Alarms & reminders** access flow when needed. If access is denied or later revoked, Agora turns Exact Execution off and falls back to inexact scheduling.

**Battery Optimization** reports whether Android is currently optimizing Agora. Tap the row to open Android's general battery-optimization settings. Agora refreshes the status when the page opens or resumes; it does not directly request an exemption for itself.

**Wake lock** keeps the CPU awake while an automation run executes, improving reliability on aggressive devices.

Exact alarms, daemon mode, and a battery-optimization exemption can improve background reliability, but they do not override network, device-vendor, or other Android background restrictions.

## Reliability

Tasks, Loops, and the Heartbeat use the normal conversation generation pipeline and do not create a second writer when a conversation is busy. Running automation displays an ongoing notification. If a target conversation is already generating, the automation reports a busy outcome instead of creating a competing run.

## Portability

Daemon mode, Heartbeat (enable, interval, active hours, prompt, model), SMS (enable, poll interval), and the notification whitelist are included in `.agora` archives under the settings category. See [Import & Export](import-export.md).
