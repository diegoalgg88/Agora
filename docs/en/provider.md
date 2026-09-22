# API Providers

Open **Settings → Providers**, then select a provider to edit its endpoint, protocol, credentials, or local models.

## Built-in Providers

Agora includes configurations for OpenAI, Anthropic, Google Gemini, DeepSeek, DashScope/Qwen, OpenRouter, Groq, AI Horde, Ollama, and Local models. Provider catalogs and endpoint behavior can change independently of the app.

For a remote provider, the Base URL field shows its effective built-in default when no override is stored. Providers without a built-in endpoint may show a placeholder instead. A blank override resolves back to the provider default where one exists. Base URL edits save automatically after a 500 ms pause; there is no separate Save action.

## AI Horde

AI Horde is a crowdsourced network of volunteer workers that serves text and image models through the official OpenAI-compatible proxy (`oai.aihorde.net`). Chat uses the standard provider flow: add an API key, sync models, and pick one — but models, speed, and availability depend on workers online at that moment.

The anonymous key `0000000000` works at the lowest queue priority with no registration. A personal key, registered at `aihorde.net/register`, earns higher queue priority over time. If you mainly want images, see the AI Horde image backend under [Image Generation](image-generation.md).

## Custom Providers

A custom endpoint can use an OpenAI-compatible, Google, or Anthropic protocol. Configure its Base URL and protocol to match the server. Model synchronization follows the selected protocol, and models can still be added manually when discovery is unavailable.

**Responses API** switches from Chat Completions to the provider's `/responses` endpoint. It is available for the built-in OpenAI provider and custom providers using the OpenAI-compatible protocol.

## API Keys

A provider can store multiple named API keys. Select the radio button beside one key to make it active for that provider. Keys can be added, edited, or deleted independently.

API keys are stored in preferences rather than the Room conversation database. `SecretCrypto` normally applies an Android Keystore AES-256-GCM envelope, but legacy plaintext remains readable and encryption failure falls back to plaintext rather than losing the value. Requests send the active credential only to the configured destination when needed. Because the Base URL determines the server contacted, verify custom endpoints carefully.

## Local Models

The **Local** provider imports chat models from GGUF files. Each entry has a model ID and alias, context size, temperature, Top P, and maximum output-token setting. An optional vision projector (`.mmproj`) adds vision support and is shown on the model row.

Use **Model Idle Retention** under Advanced to choose how long an unused local model stays loaded before Agora unloads it. Deleting a local entry removes the app-owned model and projector copies. When an edit replaces or removes a projector, Agora removes the old projector copy.

Optional exports can include provider secrets, but those secrets are unencrypted inside the archive. See [Models](models.md), [Local Models](local-model.md), [Import & Export](import-export.md), and [Privacy & Security](privacy.md).
