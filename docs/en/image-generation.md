# Image Generation

Open **Settings → Image Generation** to choose the backend, model, and default image size used by the image-generation tool.

## Backend

Two backends serve the same `generate_image` tool:

- **Standard (OpenAI-compatible)** — the default. Uses the selected model's provider credentials and its `/images/generations` endpoint.
- **AI Horde** — the crowdsourced network of volunteer workers. Requests are queued on the native Horde v2 API, so generation can take minutes, especially with the anonymous key `0000000000` (lowest queue priority). A personal key from `aihorde.net/register` earns higher priority over time; the AI Horde provider key configured under Providers is reused automatically.

## Model and credentials

The selected value identifies a configured `Provider:model`. Agora resolves the API key and base URL from that provider; this page does not maintain a separate image-generation key or endpoint. The picker favors image-capable models and can show all configured models when needed.

A model may be synchronized from a provider even if it is not enabled for ordinary chat. Whether it can actually generate images depends on the provider and model.

When the AI Horde backend is selected, the model picker lists image checkpoints currently online (e.g. Stable Diffusion, FLUX) instead of chat models; leave it empty to let any available worker pick up the request. AI Horde requests always run with `nsfw=false` and the Horde replacement filter enabled.

## Size

The default size is `1024x1024`. Available sizes and accepted options depend on the selected endpoint.

## Data flow

The prompt and relevant request parameters are sent to the selected provider, and generated media is saved with the conversation/tool output on the device. See [Privacy & Security](privacy.md).
