# System Assistant

Agora can act as the Android system assistant: long-press the power button (or your device's
assistant gesture) to open a small Agora overlay on top of any app, ask something, and get an
answer without leaving what you were doing.

## Setup

1. Open **Settings → System Assistant** in Agora.
2. Tap **Open system settings**.
3. Select **Agora** as the default digital assistant app.

The status row on that page shows whether Agora is currently the default assistant. The exact
gesture (power button long-press, corner swipe, or Home long-press) depends on your device and
Android version.

## Using the overlay

Type a prompt and tap **Send**. The answer appears inline in the overlay. Tap **Open in Agora**
to jump to the full conversation, or **Close** to dismiss the overlay.

Every overlay conversation is an ordinary Agora conversation with full access to your configured
providers and models. It appears in your conversation list like any other chat.

## Screen context

When the overlay opens over another app, Agora can see what's on your screen — like Gemini's
"Ask about screen". An **Include this screen** chip appears when screen content is available;
tap it to toggle whether that context goes with your question.

Two toggles in Settings → System Assistant control what gets captured:

- **Attach screenshot** (on by default): sends a screenshot of the current screen as an image
  attachment, so multimodal models can see layouts, images, and videos.
- **Include screen text** (on by default): sends the text visible on screen as plain-text
  context (up to 8,000 characters).

Apps that protect their content (banking, password fields, DRM video) deliver nothing — the chip
simply doesn't appear.

## Voice input

Tap the microphone button and speak — your words appear in the prompt field as you talk
(partial results stream in). Tap **Send** when ready; dictation never submits automatically.
On first use Agora asks for microphone permission. Recognition runs on-device where available
(Android 12+), otherwise via the system speech service. Turn it off with the **Voice input**
toggle in Settings → System Assistant.

## Reusing the conversation

By default each activation starts a fresh conversation. Enable **Reuse assistant conversation**
in Settings → System Assistant to keep one ongoing assistant chat instead.

## Privacy

Overlay prompts are sent to the provider you have configured, exactly like messages typed in the
main app. Nothing is sent anywhere until you tap Send.
