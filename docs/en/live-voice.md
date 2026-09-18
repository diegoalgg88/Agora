# Live Voice Call

Talk to your assistant out loud, and hear it answer back — a full voice-to-voice conversation
powered by the Gemini Live API, using your own Google API key.

## Enabling

Live voice is off by default (it's a preview feature, and native-audio models consume tokens
continuously while a call is open — not just per message).

1. Open **Settings → Assistant → Live voice call** in Agora.
2. Read the cost notice and enable **Voice calls**.

A **Call** button now appears in the assistant overlay and in the chat top bar.

## Making a call

1. Open the assistant (long-press power, or your device's gesture).
2. Tap the **Call** button. Agora opens the call screen and asks for microphone permission on
   first use.
3. Speak naturally. The model detects when you finish and answers out loud. You can interrupt
   it at any time by simply speaking again (barge-in) — it stops immediately and listens.

While the call screen is open, the microphone keeps working even if you switch apps (a small
ongoing notification appears). Tap **Hang up** to end the call. If your device has no hardware
echo canceller, Agora suggests using headphones.

The call screen shows live captions of what you and the assistant are saying — what's spoken
so far in the current exchange, plus the history of completed exchanges — along with a call
orb that reacts to the conversation. **Mute** silences your microphone without interrupting the
assistant if it is speaking.

## Conversation transcript

Each completed exchange is saved as ordinary messages in a dedicated **Voice call** conversation,
visible in your conversation list — the audio itself is never saved. When you hang up mid-turn,
whatever you had already said is kept (with an empty reply) rather than lost.

By default new calls continue the latest voice-call conversation. Disable **Reuse voice
conversation** in Settings to start a fresh one per call.

## Reconnecting

Phone connections drop; the call doesn't. Agora reconnects automatically (a brief
**Reconnecting…** indicator appears) and the conversation continues where it left off, within
Google's ~2-hour resumption window.

## Model and voice

The call uses the Gemini Live model configured in **Settings → Assistant → Live voice call**
(default: `gemini-3.1-flash-live-preview`). Live models are previews and rotate often — if a
call fails to start, update the model id there with the current name from
[Google's Live API docs](https://ai.google.dev/gemini-api/docs/live). The spoken voice
(default: `Kore`) can also be changed to any prebuilt voice name.

## Privacy

Audio is streamed directly from your device to Google's Gemini Live endpoint with your API key
— the same BYOK trust model as every Agora provider. Audio is never recorded or stored on the
device or by Agora; only the text transcript is saved locally. Calls use the microphone only
while active.
