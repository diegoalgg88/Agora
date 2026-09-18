# System Assistant — Module Contract

**Scope:** Agora's registration as the Android **default digital assistant app** and the overlay
shown on the assistant gesture (long-press power button / corner swipe / long-press Home,
device-dependent). Code lives in `app/src/main/java/com/newoether/agora/assistant/`
(`AgoraVoiceInteractionService`, `AgoraVoiceInteractionSessionService`,
`AgoraVoiceInteractionSession`, `AssistantActivity`, `AssistantOverlay`,
`AssistContextCapture`, `AssistantVoiceInput`, `AssistantVoicePermissionActivity`),
`app/src/main/java/com/newoether/agora/assistant/live/` (Live API voice calls, Phase 4), and
`app/src/main/java/com/newoether/agora/ui/assistant/VoiceModeActivity`.

## Two entry paths (device-dependent)

- **Stock Android (Pixel, AOSP):** the picker lists `VoiceInteractionService` apps. The system
  binds `AgoraVoiceInteractionService` → `AgoraVoiceInteractionSession` shows the overlay and
  receives Assist API screen context (`AssistStructure` text + screenshot).
- **Samsung One UI (verified on-device, 2026-09-16):** the default-assistant picker only lists
  activities with an `android.intent.action.ASSIST` intent filter (this is how Kai, Alexa and
  Euria appear — none of them ships a VoiceInteractionService). `AssistantActivity` declares
  that filter and hosts the same shared overlay UI as a translucent activity. **No Assist API
  data is available on this path**: the screen-context chip simply never appears.

Both hosts share `AssistantOverlayState` / `AssistantOverlayContent` (`AssistantOverlay.kt`) so
behavior (send pipeline, voice dictation, conversation reuse) is identical.

**Reference:** Gemini's activation flow (long-press power → overlay → "Ask about screen"),
the Android Assist API (`developer.android.com/training/articles/assistant`), and the
Home Assistant Companion app as a production open-source precedent.
Plan and owner decisions: `docs/PLAN-20260915-SYSTEM-ASSISTANT.md`.

## Category definition

The system assistant is a **system-level entry point into the ordinary chat pipeline**, not a
tool and not automation. It produces a normal conversation (`origin = "assistant"`) that appears
in the conversation list like any other chat.

## Invariants

1. **No parallel pipeline.** Sending from the overlay routes through
   `TaskExecutionEngine.runOnce` (the same headless single-shot engine used by automation),
   which reuses `GenerationManager`. Never create a separate generation/queue/Stop path for
   the overlay (`development/README.md` §4.6).
2. **Fail closed when not default.** The service is bound by the OS only while Agora is the
   selected assistant (`BIND_VOICE_INTERACTION` is signature-level, granted by the system).
   Settings must show the real status (read from
   `Settings.Secure["voice_interaction_service"]`) and deep-link to
   `Settings.ACTION_VOICE_INPUT_SETTINGS`; never imply the feature works without it.
3. **Conversation identity.** Overlay conversations use `origin = "assistant"` and
   `taskId = null` (so they appear in the ordinary conversation list). Per the owner decision
   (2026-09-15), each activation creates a **new** conversation unless the
   `assistantReuseConversationEnabled` toggle is on, in which case the newest
   `"assistant"`-origin conversation is reused (`ConversationRepository.getConversationByOrigin`).
4. **No lockscreen launch.** `supportsLaunchVoiceAssistFromKeyguard` stays `false`; the overlay
   is not designed for the lock screen.
5. **No overlay window permission.** The session window uses the framework's voice-interaction
   z-order. Never add `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY` for this feature.
6. **Compose in the session window** requires manual `LifecycleOwner` / `ViewModelStoreOwner` /
   `SavedStateRegistryOwner` (see `SessionOwners` in `AgoraVoiceInteractionSession.kt`); the
   window has no hosting Activity.
7. **One toggle, one owner.** Overlay behavior toggles live in `AssistantToolSettings`
   (same DataStore store as the assistant device tools, to respect the 999-line source-size
   policy on `SettingsManager`), are exposed through `SettingsRepository`, exported/imported by
   `PortableSettingsArchive` (`assistantReuseConversationEnabled`,
   `assistantAttachScreenshotEnabled`, `assistantIncludeScreenTextEnabled`,
   `assistantVoiceInputEnabled`, `liveVoiceEnabled`, `liveVoiceReuseConversationEnabled`,
   `liveVoiceModelId`, `liveVoiceVoiceName`), and cleared by the full settings reset.
8. **API-level guards.** Any API above minSdk 26 (e.g. on-device `SpeechRecognizer`, API 31+)
   must be gated with the corresponding availability check and a fallback (AGENTS.md §3.2.9).
9. **Live voice calls are a transport, not a pipeline.** The Gemini Live WebSocket may not
   create a second generation/queue/Stop lifecycle. Turns commit only through
   `ChatDao.createCompletedRunWithMessages` (fresh Run born terminal, live-slot fence respected,
   Room as sole durable truth). The microphone foreground service starts only from the
   foreground call screen, never from background/automation. No audio is persisted.

## Settings UX

- Own group **Assistant** (`settings_group_assistant`) in `SettingsScreen.kt`, with two
  categories: **System Assistant** (`SettingsAssistantPage.kt`) and **Live voice call**
  (`SettingsLiveVoicePage.kt`).
- The page shows: default-assistant status (read-only), a deep-link row to the system picker,
  and the behavior toggles as plain `SettingsItem` + `Switch` rows
  (`development/settings-ui-ux.md`).
- All labels live in `res/values*/assistant_strings.xml` and must exist in every supported
  locale (`development/application-ui.md` §3).

## Portability

`assistantReuseConversationEnabled`, `assistantAttachScreenshotEnabled`,
`assistantIncludeScreenTextEnabled`, `assistantVoiceInputEnabled`,
`liveVoiceEnabled`, `liveVoiceReuseConversationEnabled`, `liveVoiceModelId` and
`liveVoiceVoiceName` are exported and imported by `PortableSettingsArchive` and cleared by the
full settings reset (`AssistantToolSettings.removeAll`).

## Phased scope

- **Phase 1:** service registration, minimal overlay (prompt + send + inline
  response + "Open in Agora"), conversation creation/reuse, Settings page, docs page
  (`docs/*/assistant.md`).
- **Phase 2 (current):** screen context via the Assist API — `onHandleAssist` delivers the
  `AssistStructure` view hierarchy (flattened to text, 8 KB cap, `AssistContextCapture`),
  `onHandleScreenshot` delivers a `Bitmap` persisted as PNG under
  `filesDir/attachments/assistant/`. Both attach to the outgoing message through the ordinary
  `AcceptedInputGraphWriter` fields (`images` + `attachmentMeta`), gated by the
  `assistantAttachScreenshotEnabled` / `assistantIncludeScreenTextEnabled` toggles (default on)
  AND the per-send "Include this screen" chip. `FLAG_SECURE` apps simply deliver nothing; the
  chip stays hidden.
- **Phase 3 (current):** voice input via `SpeechRecognizer` (`AssistantVoiceInput`): partial
  results stream into the prompt field — replacing only the dictated segment, so text the user
  typed before tapping the mic survives (`AssistantOverlayState.joinDictationPrompt`); recognition is
  on-device on API 31+ (`isOnDeviceRecognitionAvailable` guard) with the network recognizer as
  fallback; sending stays manual (no auto-submit); no audio is persisted. `RECORD_AUDIO` is
  requested at runtime through the `AssistantVoicePermissionActivity` trampoline (session windows
  cannot host permission dialogs). Gated by the `assistantVoiceInputEnabled` toggle (default on).
- **Phase 4 (current):** voice-to-voice calls via the Gemini Live API WebSocket
  (`assistant/live/`, `ui/assistant/VoiceModeActivity`). Entry: a "Call" button in the overlay,
  shown only when `liveVoiceEnabled` is on (default off). `VoiceModeActivity` starts
  `LiveVoiceForegroundService` (`foregroundServiceType="microphone"`, foreground-only start,
  `START_NOT_STICKY`) and `LiveVoiceSessionController` (capture → `GeminiLiveClient` →
  playback; automatic reconnect via `sessionResumption` surfaced as a brief "Reconnecting…"
  state; `contextWindowCompression` from v1). **Transport exception (scoped):** completed turns
  persist through `ChatDao.createCompletedRunWithMessages` — one fresh Run created terminal
  (COMPLETED) with a USER + MODEL pair from the server's input/output transcriptions — never
  through the ordinary generation pipeline. Room stays the durable truth; there is no second
  generation, queue or Stop path. Audio is never persisted; a turn interrupted by the user
  (barge-in) is dropped, and an un-finished spoken turn at hang-up is persisted with an empty
  model reply rather than lost. Function calling over the Live socket is explicitly deferred
  (Fase 4.1). Settings: `SettingsLiveVoicePage` (group Assistant) — enable gate, conversation
  reuse, free-text model id (default `gemini-3.1-flash-live-preview`, must be re-verified against
  the docs on major updates), free-text voice name (default `Kore`), privacy + cost notices.
  Portability: `liveVoiceEnabled`, `liveVoiceReuseConversationEnabled`, `liveVoiceModelId`,
  `liveVoiceVoiceName`. Plan: `docs/PLAN-20260915-SYSTEM-ASSISTANT.md` §10.
- **Call screen (Fase 4.1, 2026-09-17):** `VoiceModeScreen` renders live captions in the top
  area — committed turns plus the in-flight user/model caption lines via the pure
  `LiveTranscriptLog` (display state only; Room stays the durable truth), driven by the
  controller's `onTranscript`/`onTurnCommitted` callbacks with a UI version counter for
  recomposition. **Mute semantics:** `setMuted` gates ONLY outgoing mic frames — it must never
  send `audioStreamEnd`, because that frame commits the pending user turn and a committed turn
  interrupts in-flight model generation (the "mute silences the assistant" bug). The visual
  language follows Agora themes exclusively (scheme-driven orb/halo/transcript bubbles, no
  hardcoded colors) and all continuous motion honors `LocalAgoraMotionPolicy` (reduce-motion
  swaps the breathing orb for a static presence disc); `AssistantAppTheme` provides the motion
  policy for every assistant surface.

## Known limitations

- Only one app can be the default assistant; the user must switch away from Gemini/other
  assistants manually in system settings.
- The activation gesture is OEM-dependent (Samsung/Pixel/etc. remap it); Agora cannot control
  or detect the gesture itself, only the resulting session.
- Apps with `FLAG_SECURE` never deliver screenshots or assist structure; the overlay must treat
  screen context as best-effort.
- On Samsung One UI the picker ignores `VoiceInteractionService` apps entirely; only the
  `ACTION_ASSIST` activity path is listed there, and that path receives no screen context.
  Detection of "am I the default assistant" must therefore accept either our
  VoiceInteractionService or our AssistantActivity being selected.

## Reclamation and surface-state rules

- Assist screenshots are staged in `filesDir/attachments/assistant/` **before** the user decides
  whether to attach them. Every overlay activation enqueues an attachment-orphan reconcile
  (`ConversationRepository.scheduleAttachmentReconcile`), and the ordinary
  `AttachmentOrphanSweeper` sweep includes the directory (1 h minimum age). Any screenshot that
  became message-owned is protected by the sweeper's transactional Room-reference check —
  including the send-race where the PNG finishes writing after the send read `screenshotPath`.
  Never add a parallel deletion path for this directory (invariant: no attachment deletion
  without Room reference query).
- `AssistantActivity` is `singleTask` without `excludeFromRecents`; a re-invoked gesture calls
  `onNewIntent`, and `AssistantOverlayState.resetForNewActivation` clears terminal surface
  state (prompt, response, conversation link) while an in-flight generation keeps its UI so the
  user still sees its result.
- A `TaskExecutionEngine.Result.Busy` outcome is surfaced as the distinct
  `assistant_overlay_busy` message (status `Busy`, not `Error`); the prompt stays intact for a
  retry. Engine `Failure` reasons still go only to `DebugLog`, never to overlay UI text.
