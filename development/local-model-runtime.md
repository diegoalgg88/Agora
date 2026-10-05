# Local Model Runtime Contract

Status: authoritative embedded inference lifecycle contract, 2026-10-04 (amended to cover LiteRT-LM).

This document owns process-wide admission, native model residency, identity changes, cancellation,
and idle offload for Agora's embedded local-model paths: the llama.cpp Chat and Embedding paths and
the LiteRT-LM engine for `.litertlm` bundles. Conversation/Run lifecycle
remains owned by [message-generation.md](message-generation.md), while portability of the device-local
retention setting remains owned by [import-export.md](import-export.md).

## 1. Canonical owners

- `LocalModelRuntime` is the one process-wide owner of embedded local-model admission and the one
  resident native model/context, across both engine families (llama.cpp and LiteRT-LM).
- `LocalModelTaskQueue` is the one FIFO admission boundary shared by Local Chat, Local title
  generation, Local Embedding work, and LiteRT-LM Chat work.
- `LlamaChatEngine` owns a resident Chat native handle and its replaceable multimodal projector
  substate. `LlamaEngine` owns a resident Embedding native handle only while the runtime identifies
  that resident as Embedding.
- `LiteRtChatEngine` owns a resident LiteRT-LM SDK Engine for `.litertlm` bundles. Its
  conversations are created fresh per request and never survive a request.
- `AppContainer` binds the one app-lifetime idle-retention settings flow to the runtime, supplies
  Android's process native-library directory for llama.cpp backend initialization, and binds the
  process cache directory for LiteRT-LM engine cache files via `initializeLiteRt`.

No caller may load, unload, reset, replace, or generate with an embedded model outside this owner,
in either engine family. Remote Providers, including a PC-hosted Qwen endpoint, do not enter this
queue. There is never concurrent native execution across engines, and never two resident engines:
a GGUF request and a `.litertlm` request alternate through the same permit with full
unload-before-load switching.

### Android CPU backend initialization

The Android build uses shared llama.cpp/ggml libraries and dynamic CPU backends. It packages the
seven upstream Android ARM variants from `android_armv8.0_1` through `android_armv9.2_2`; the
`android_armv8.0_1` module is the compatible arm64 baseline when no higher-scoring feature set is
available. KleidiAI, Vulkan, and OpenMP remain disabled.

`AppContainer` passes `applicationInfo.nativeLibraryDir` once to `LocalModelRuntime`. The runtime
canonicalizes that directory and calls the existing `LlamaEngine` JNI owner, which loads the best
compatible CPU module with `ggml_backend_load_all_from_path` before initializing llama.cpp. Chat and
Embedding model-load entry points must not independently load or initialize backends. Repeated
initialization from the same process directory is a no-op; a different directory is an invariant
violation. If no compatible CPU backend loads, Local Chat reports its ordinary model-load failure and
Local Embedding returns no result without making application startup fail.

## 2. Resident identity and switching

Exactly one of these identities may be resident:

- `Chat(canonicalModelPath, nCtx)`;
- `Embedding(canonicalModelPath)` using the fixed native Embedding context parameters;
- `LiteRtChat(canonicalModelPath, backend, visionCapable, audioCapable)` for the embedded
  LiteRT-LM engine: vision and audio executors are constructed with the engine, so both
  capability flags participate in the identity exactly like the backend.

Chat and Embedding are different identities even when their canonical model paths match. Chat
sampling values such as temperature, top P, frequency/presence penalties, and maximum output tokens
do not construct the native context and therefore do not change identity. The same holds for
LiteRT-LM: sampler values, thinking configuration, and tools are per-conversation values and do not
change the engine identity. The requested backend and vision capability DO participate in the
LiteRT-LM identity, because both can only be applied while constructing the SDK Engine: switching
backend or vision capability is a full unload-before-load replacement, never resident substate
mutation.

### LiteRT-LM engine residency and per-request conversations

The resident part of the LiteRT-LM identity is the SDK Engine (weights plus compiled delegate).
Conversations are created fresh for every request inside the runtime's FIFO block and closed before
the block ends. Reusing a conversation across requests is prohibited: the SDK's
`cancelProcess()` does not roll back internal conversation state (upstream b/450903294), and
Agora's branching/regeneration invalidates any cached conversation prefix, so any surviving
conversation state would be corrupt. The cost of this rule — a full prefill per request — is the
price of correctness, identical in spirit to the GGUF multimodal cache-invalidation rule.

The Auto backend may retry once on CPU when a GPU-resident engine cannot open a conversation
(delegate graph compilation failing per-bundle after engine init succeeded). The retry is a
fresh sequential admission through the same FIFO after the first returns — never nested inside
the first block, because the permit is a non-reentrant `Semaphore(1)` and a nested admission
would deadlock every local model behind it. An explicitly requested CPU or GPU backend never
switches silently: its failures are reported as-is.

The native Chat context uses hardware-derived thread counts for both single-token decode
(`n_threads`) and batch prefill (`n_threads_batch`): computed once at context construction by
counting performance cores (those whose `cpuinfo_max_freq` is >= 2 GHz, clamped to [1, 6]),
falling back to half the online processor count when the sysfs topology is unreadable, then to 4.
The derived value is constant for the context's lifetime, requires no caller input, and
therefore does not participate in resident identity.

New Local Chat model records created through Settings or onboarding default to `nCtx=16384` and
`maxTokens=1024`. Existing records are not migrated: the serialized `LocalChatModelConfig` fallback
for a missing legacy `nCtx` remains 2048, and an explicitly stored context size remains unchanged.

A task requesting the current identity reuses its resident model. Reused Chat identity also retains
the native context's proven text KV prefix. Reused Embedding identity clears per-input context memory
through the native Embedding path. A different path, mode, or Chat `nCtx` closes the old resident
completely before the replacement load begins. If replacement loading fails, no model remains
resident and the request fails through its ordinary Local error path.

The multimodal projector is replaceable Chat substate rather than process identity. It is loaded only
for an image request, reused only for the same projector path, replaced when that path changes, and
never permits concurrent mutation of the resident Chat engine.

### Text KV prefix ownership

The resident native `ChatHandle` owns both its llama.cpp context memory and a ledger containing only
token IDs whose decode completed successfully in that context. A new text prompt reuses their token
longest-common-prefix, removes memory positions at and after the divergence, and decodes only the
uncached suffix. A complete prompt match retains all but the final token so that this request decodes
at least one token and obtains current sampling logits.

The complete newly rendered prompt must fit `nCtx` before the resident cache is modified. A token or
batch enters the ledger only after its corresponding `llama_decode` succeeds. Cancellation between
successful batches may therefore retain the known prefix. Any nonzero decode result can leave a
partially processed ubatch, so it clears both native memory and the ledger. If partial sequence
removal is unsupported, fails, or leaves memory inconsistent with the retained prefix, the same full
clear occurs before decoding the prompt from zero.

Image embeddings cannot be represented by the text token ledger. Template, image-read, allocation,
and multimodal tokenization failures that occur before native evaluation leave the prior text cache
untouched. Immediately before mtmd evaluation, the runtime clears the text cache; every terminal path
after evaluation starts clears native memory and the ledger again. The next text request after an
image request therefore starts with an empty context.

## 3. Chat templates and thinking

Chat prompt rendering uses the explicit template embedded in the GGUF through llama.cpp's official
`llama-common` Jinja owner. A missing, invalid, or inapplicable model template fails the request; the
runtime must not substitute ChatML, a model-family prompt, or another generic fallback. The parsed
template bundle is Chat resident substate and is released before its model.

For LiteRT-LM, the chat template is owned by the `.litertlm` bundle itself: the SDK renders the
prompt through the bundle's embedded template and Agora never renders, overrides, or substitutes a
template on that path. A bundle without a usable template fails at conversation use, fail-closed
exactly like the GGUF path. Thinking arrives through the SDK's separate channel and maps to the
shared thought-event stream; the shared incremental thinking parser remains a safety net for
reasoning delimiters a bundle emits as ordinary text.

Each request passes its effective `thinkingEnabled` value into the model template. This value may
change the rendered prompt but does not construct the model/context or change resident identity.
Native template parsing remains authoritative for typed tool-call events. It must not bypass the
shared incremental thinking parser for ordinary text: model-emitted reasoning delimiters, including
supported space-bearing channel forms, are recovered across arbitrary callback chunk boundaries while
matching markers inside Markdown inline or fenced code remain literal.

Each Local request also carries its effective temperature, top P, maximum output tokens, frequency
penalty, and presence penalty into both text and multimodal native generation. Nullable penalties
become neutral zero. Both native sampler chains use llama.cpp's penalties sampler with its default
64-token history window, neutral repeat penalty `1.0`, and the captured frequency/presence values;
they must not replace those values with a repeat-penalty approximation.

### Tool capability probing

Whether a model template "supports tool calling" is decided by llama.cpp's Jinja capability probe
(`jinja::caps_get`): the embedded template is executed with probe inputs (an OpenAI-shaped `tools`
array plus a `tool_calls`/`tool` history) and only templates that actually read those fields report
capability. Agora fails closed when a request needs tools (tool definitions attached, or tool history
in context) and the probed template lacks the capability — never substituting a generic tool prompt,
which could silently apply the wrong role/control-token protocol.

The capability probe must target **the same template the apply path renders with**. A GGUF may
embed a separate `chat_template.tool_use`; `common_chat_templates_apply` selects it whenever tools
are present, so the caps probe (`common_chat_templates_get_caps`, `with_tools = true`) must select
it too — probing only the default template misjudges split-template models whose default template
never mentions tool fields. Legacy or flat-schema templates that genuinely cannot render llama.cpp's
OpenAI-shaped tool input are still rejected by design; upstream's generic-fallback tolerance is not
adopted. Rejection diagnostics are content-free on both layers: capability flags natively, and
template length plus tool-field reference booleans in the Provider — never the template source or
prompt content.

## 4. Strict FIFO admission

Every submitted Local task is counted as queued-or-active before it waits for the process permit.
The permit is fair FIFO: one complete Local request owns it from identity selection/load through all
native work, callbacks, and request cleanup. There is no concurrent Chat/Chat, Chat/Embedding,
Embedding/Embedding, GGUF/LiteRT-LM, or LiteRT-LM/LiteRT-LM native execution: both engine families
share the single permit, and switching engine family always unloads the current resident before
loading the requested one.

An active task is never preempted by a newer task or a different requested model. A cancelled waiter
is removed from admission and cannot disturb the relative order of remaining waiters. Task failure or
cancellation still releases its queue ownership and participates in the same final idle transition.

Stop targets only the currently active Chat engine through its thread-safe native cancellation path.
It does not cancel Embedding work, unload a model directly, cancel waiting Local tasks, or acquire the
permit held by the active native generation. For the active LiteRT-LM conversation, Stop reaches the
request-scoped cancel handle the Provider registered in the stream scope; the conversation dies with
its request, so Stop never corrupts resident engine state.

## 5. Idle offload lifecycle

Idle means there are no queued or active Local tasks. A model may remain resident while idle for the
configured retention duration.

1. Arrival of any Local task invalidates and cancels the current idle deadline before admission.
2. A deadline starts only when the final queued-or-active task has completely unwound.
3. Expiry re-enters the same FIFO permit as conditional maintenance and unloads only after proving
   that the queue is still empty for the captured idle epoch.
4. A task arriving at the expiry boundary linearizes either before unload, cancelling or invalidating
   it, or after the completed unload and then loads its requested identity normally.
5. A setting change while idle invalidates the old epoch and restarts the deadline from the change.
   A setting change during work affects the deadline created after the final task completes.
6. Zero minutes unloads immediately after the final queued-or-active task completes. It never unloads
   between already submitted tasks.

Only the configured duration persists. An in-flight deadline or remaining elapsed time is not
restored after process death; the new process begins with no resident model and no inherited timer.

## 6. Setting and UI contract

`local_model_idle_retention_minutes` accepts only `0, 1, 2, 5, 10, 15, 30`; invalid or absent values
normalize to the five-minute default. It is stored in this device's DataStore and exposed at
Provider -> Local -> Advanced as the discrete `Model idle retention` slider. Zero is presented as
immediate offload; positive presets are presented in minutes.

The default resource and every supported locale define the same localized key and placeholder set.
The setting is device-local: it is excluded from portable Settings export/import and survives a
Settings `REPLACE`, as specified by [import-export.md](import-export.md).

`local_low_context_mode_enabled` is a separate device-local Boolean and defaults to `false`. It is
exposed at Provider -> Local -> Advanced as the default for new and existing conversations that have
no explicit override. A conversation or New Chat stores a nullable override: `null` inherits the
current Provider default, while an explicit `true` or `false` continues to win if the default later
changes. The request effect remains limited to ordinary embedded Local Chat as specified by
[message-generation.md](message-generation.md); it does not change model residency, native context
identity, Compact/title prompts, Ollama, or remote Providers. It applies identically to both engine
families: GGUF records and `.litertlm` records both face small native contexts against the full
default prompt (a 4096-token bundle overflows on the default assembled prompt — verified on
device), so the stripping covers both.

### LiteRT-LM record contract

`.litertlm` records live in the same `LocalChatModelConfig` DataStore list as GGUF records,
discriminated by `format` (`"gguf"` default for legacy rows | `"litertlm"`). LiteRT-LM-only fields
(`backend` `auto|cpu|gpu`, `topK`, `visionCapable`, `mtp`) carry defaults that keep legacy rows decoding
as GGUF. For `.litertlm` records, `nCtx` is only Agora's history-truncation budget
(`LITERTLM_DEFAULT_NCTX = 4096` for new records); it is never forwarded to the SDK's
`EngineConfig.maxNumTokens`, which keeps the bundle's own preset. `mmprojPath` is always empty for
`.litertlm` records: vision weights live inside the bundle.

`mtp` is a per-model opt-in for Multi-Token Prediction (speculative decoding). The field,
UI toggles, and persistence are wired end-to-end (UI-ready). The engine side is gated on the
SDK: per-conversation speculative decoding (`ConversationConfig.enableSpeculativeDecoding`,
with lazy drafter init and no process-global toggle) exists only in the SDK's `main`
(upstream `bc16765a`), after the pinned 0.17.1 stable — never through the process-global
experimental toggle, which Agora sources must not reference. Unlocking is a one-liner at
the mapper's conversation-config site once a stable SDK ≥0.18 ships. A future SDK upgrade
is not drop-in: `main` also refactors the wrapper Agora consumes (Capabilities API →
ModelInfo, `Role.MODEL` → "assistant", priority-ordered backends — the natural shape for
NPU), so plan a dedicated adaptation pass when bumping the pinned version.

The hosted catalog's `defaultConfig.topK` travels into the record at download time; the
catalog lists only device-verified bundles (the litert-community gold standard), never
community conversions with a fragile Conversation API.

## 7. Native streaming and telemetry

Native text and multimodal decoding check cancellation after every decoded token. Complete UTF-8 is
delivered through the blocking callback after at most four decoded tokens or 64 complete bytes,
whichever occurs first. Callback rejection stops generation without retrying rejected bytes. Before
any other terminal result, all already-decoded complete bytes are delivered; an incomplete final
UTF-8 sequence is an explicit failure rather than replacement or truncation.

Debug telemetry may record model/context setup time, prefill/decode/request duration, image count,
token counts, terminal category, and tokens per second. It must never record prompts, generated text,
message content, model paths, image paths, or other private payloads.

## 8. Prohibited behavior

Never introduce a second Local lock, model cache, lifecycle manager, offload timer, Provider-local
fallback, per-caller unload callback, or identity definition — for either engine family. Never
unload directly from a timer without reacquiring the canonical permit and revalidating the idle
epoch. Do not restore idle deadlines across processes, interrupt active native work to honor a
deadline, or export this setting. Never invent a fallback chat template or bypass the official
model-owned Jinja path (GGUF) or the bundle-owned template (LiteRT-LM). Never keep a LiteRT-LM
Conversation alive across requests or reuse one after `cancelProcess()`. Never construct a LiteRT-LM
`Engine` outside the runtime's FIFO block, and never use the SDK's Flow streaming variant (its
collector cancellation leaks the native stream, upstream #2718) — streaming goes through the
callback overload wrapped in Agora's own `callbackFlow`.

## 9. Required verification

Focused verification must cover FIFO ordering, no native overlap, cancelled-waiter removal,
Chat/Embedding/path/context identity changes, unload-before-load, failed replacement, same-identity
reuse, active-Chat-only Stop, and Embedding input isolation. Idle tests must cover arrival
cancellation, no countdown while queued/active, last-task deadline start, setting-change restart,
zero-minute behavior, expiry-versus-arrival linearization, and unload through the same permit.

Chat-template verification must cover explicit-template enforcement, official Jinja ownership,
request-level thinking control, UTF-8-safe prompt transfer, and absence of generic fallbacks.
Native-streaming verification must cover both generation loops, exact batch bounds, UTF-8 boundary
safety, terminal flushing, callback rejection, per-token cancellation, and content-free telemetry.
Text-cache verification must cover same-identity reuse, token LCP divergence, exact-match one-token
replay, prompt-capacity validation before mutation, failed truncation, decode failure, cancellation
between successful batches, generated-token ledger ordering, and multimodal invalidation. Android
backend verification must cover shared dynamic builds, all seven upstream Android ARM CPU modules,
armv8.0 baseline packaging, explicit loading from `applicationInfo.nativeLibraryDir`, initialization
before either model-load path, and absence of KleidiAI, Vulkan, and OpenMP.

Settings tests must cover the exact presets/default/normalization, DataStore read/write, one
AppContainer binding, Local Advanced placement and slider commit behavior, locale key/placeholder
parity, portable-export absence, and Settings Replace preservation. The project full build remains
required; build success alone does not prove real-device memory release or model reload latency.

LiteRT-LM verification must cover cross-engine unload-before-load (GGUF <-> .litertlm alternation
leaves at most one resident), engine reuse on same identity, conversation-per-request lifecycle
(a Stop or exception never poisons the resident engine), backend-change identity replacement, the
record roundtrip (legacy rows decode as GGUF), and the low-context-mode exclusion for `.litertlm`.
These require a device with a valid bundle: build success alone proves nothing about GPU delegate
behavior or memory.

### Deferred follow-ups (not in v1)

- Speculative decoding / MTP: **shipped** (per-model `mtp` opt-in: field, toggles,
  persistence, engine-side unlock). The pinned SDK (0.17.1) exposes speculative decoding
  only through the process-global `ExperimentalFlags.enableSpeculativeDecoding`, read
  exclusively at `Engine` construction — `LiteRtChatEngine.load()` sets the flag from the
  registered opt-in and resets it in a `finally`, which makes the global effectively
  per-engine because this process constructs LiteRT engines only under the FIFO permit.
  `mtp` is part of the resident identity (toggling reloads the engine) and explicit
  `false` is passed for non-MTP models so drafter-bearing bundles cannot default-enable
  it against the registered opt-out. On-device validation note (upstream #2227): MTP is
  a measured speedup on Adreno GPUs (the S22 Ultra family) and a measured regression on
  PowerVR (Tensor G6) — the opt-in stays per-model rather than global for exactly this
  reason. When a stable SDK past 0.17.1 ships (Google Maven's latest published artifact
  is 0.17.1 as of 2026-10-04; upstream `bc16765a` adds `ConversationConfig
  .enableSpeculativeDecoding`), the adaptation pass can swap the engine-side scope for
  the per-conversation parameter — the swap site is documented at the mapper's
  conversation-config construction.
- NPU backend: **shipped as plumbing** (`backend` `auto|cpu|gpu|npu`; the SDK resolves NPU
  delegate libraries from the app's native library directory at engine construction).
  Verified by compilation and contract tests only — no NPU device was available for the
  on-device smoke pass; on such devices it is compile-level unverified behavior. When a
  stable SDK ≥0.18 ships, prefer its priority-ordered backends over the single-backend
  selection (upstream `3f6f7486`), which also supersedes this enum's shape. NPU-specific
  per-SoC bundles exist upstream (`litert-community` publishes `gemma-4-E2B-it_Google_Tensor_G5/G6`,
  `_qualcomm_sm8750`, `_intel_LNL/PTL` variants); the hosted catalog lists only
  device-verified bundles, so those variants enter the catalog only after on-device
  validation.
- The SDK's audio executor: **engine-side plumbing shipped and user-reachable** — the
  `audioCapable` record flag configures the SDK audio backend at engine construction, and
  both the add-model and edit-model dialogs expose the toggle (default off). Content
  mapping remains deliberately absent: Agora messages do not carry audio attachments yet,
  so there is nothing to map — the flag prepares the engine for the day chat audio
  attachments exist. Enabling it on a bundle without audio weights is a no-op at engine
  construction, not an error. Verification path (upstream research 2026-10-04): the
  audio-capable test bundle is `google/gemma-3n-E2B-it-litert-lm` on HuggingFace (full
  multimodal: text, image, video, audio input) — import it manually, enable the audio
  toggle, and confirm engine init with the audio backend on the S22 Ultra; deeper
  content-mapping verification waits for chat audio attachments.
- Repetition penalty (multiplicative, HuggingFace-style): **shipped for `.litertlm`** —
  the setting flows through the same chain as the other penalties (DataStore default →
  per-conversation override → ProviderConfig → `RepetitionPenaltyConfig.repetitionPenalty`,
  with the SDK's `>= 1.0` requirement guarded at the mapper: sub-1.0 values resolve to
  the engine default instead of failing conversation creation). Remote OpenAI-compatible
  requests never carry it. The GGUF/llama.cpp path does not forward it yet:
  `llama_chat_jni.cpp` hardcodes `penalty_repeat = 1.0f`; wiring it there is a native
  change (JNI parameter + rebuild) tracked as a separate follow-up.
- `EmbeddingEngine` of the SDK: **rejected**. Agora's embeddings (memory, RAG, semantic
  search) remain llama.cpp-only: the GGUF embedding path works, `.litertlm` embedding bundles
  do not meaningfully exist in the wild, and a second embedding engine would either break
  the one-resident rule or gain nothing. Revisit only if both conditions change.
