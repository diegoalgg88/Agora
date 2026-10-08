# MCP Apps (SEP-1865) — Implementation Plan

Status: implemented 2026-10-06 (Phases 1-4). The authoritative contract is now `development/mcp.md` §9;
this file is kept only as the planning record. Deviation from the plan: `ui/message` is refused in v1
(no composer-draft prefill), `resources/read` from a view is refused, and `serverResources` is not
advertised.
Supersedes the unreviewed third-party plan (it targeted APIs that do not exist in this repo).

## 0. Verified facts about the current code

| Fact | Where |
|---|---|
| `ToolProvider` API is `definitions(ctx)`, `execute(name, arguments: String, ctx)`, `executeEvents(...)`, `handles`, `presentationMetadata` | `tool/ToolProvider.kt` |
| `ToolExecutionResult(text, images, structuredContent: String?, displayText, isError, transcribeImages)` is the only tool output type | `tool/ToolProvider.kt` |
| `McpToolProvider.definitions` maps `registry.enabledTools()`; no visibility filter | `tool/McpToolProvider.kt` |
| `McpRemoteTool(name, description, inputSchema)`; plain data classes, **not** `@Serializable` | `mcp/McpModels.kt` |
| `listTools()` ignores `_meta`; `initialize` sends `capabilities = {}` | `mcp/McpProtocolClient.kt` |
| No `resources/read`, no resource handling anywhere | `mcp/*` |
| Tool results persist as `MessageSegment` JSON (`toolResult`, `toolResultText`, `toolStructuredResult`, `toolImages`) and `ToolCallData` | `model/ChatMessage.kt`, `viewmodel/GenerationToolBatchEffectExecutor.kt` (`GenerationToolOverlay.complete`) |
| MCP tool detail UI is `McpResultContent` inside `ToolDetailContent` | `ui/chat/message/ToolResultContent.kt` |
| Segment JSON is schema-less in Room → new optional fields need **no** Room migration | `model/ChatMessage.kt` |
| No WebView and no `androidx.webkit` dependency | `gradle/libs.versions.toml` |

## 1. Design decisions (and why)

1. **No parallel pipeline.** The MCP App is a *view of a completed tool result*. The tool call still goes
   `McpToolProvider.executeEvents → GenerationToolExecutor → GenerationToolOverlay.complete`. No callback
   from the provider to `ChatViewModel`, no live `WebView` held in state (`AGENTS.md` §3.2 #3/#4, §4.6).
2. **Durable reference, not durable payload.** Persist only `(serverId, resourceUri)` on the segment. The
   `tool-result` sent to the app is rebuilt from fields already persisted (`toolResultText`/`toolResult`,
   `toolStructuredResult`, `toolImages`). The HTML is re-read on demand from the server (bounded cache).
3. **Demand-driven (§4.4).** No `resources/read`, no WebView, nothing until the user opens the tool detail and
   taps *Open interactive view*. At most one live WebView per visible tool detail; destroyed on dispose.
4. **Model never sees UI plumbing.** UI fields are never projected into Provider context
   (`ProviderMessageProjector`, `MessagePayloadProjector` untouched). Tools with `visibility == ["app"]` are
   excluded from `definitions()` and from `handles()` for model calls.
5. **Fail closed.** Missing `WebViewFeature.WEB_MESSAGE_LISTENER`, bad scheme/mime, oversize HTML, server
   mismatch, or non-visible tool → the normal text/structured result renders, no error to the model.
6. **Capability is advertised last** (end of Phase 3), so servers never return UI-only responses before
   the host can render them.

## 2. Phases

### Phase 1 — Protocol + registry (no UI)  ← implemented first

| File | Change |
|---|---|
| `mcp/McpModels.kt` | `McpRemoteTool` += `uiResourceUri: String?`, `uiVisibility: Set<String>` (default `{model, app}`). Add `McpUiVisibility`, `parseMcpToolUiMeta(meta: JsonObject?)` (pure, internal): reads `_meta.ui.resourceUri` then deprecated `_meta["ui/resourceUri"]`; accepts only `ui://`; filters visibility to `model`/`app`, empty → default. `McpToolDescriptor` += `isModelVisible`, `isAppVisible`. Add `McpUiResource(uri, html, meta)` + `parseMcpUiResourceContents(result)` (pure): requires mime `text/html;profile=mcp-app`, `text` or base64 `blob`, `MAX_UI_RESOURCE_BYTES = 2 MiB`. |
| `mcp/McpProtocolClient.kt` | `listTools()` fills `uiResourceUri/uiVisibility` via `parseMcpToolUiMeta`. New `suspend fun readUiResource(uri): McpUiResource` (same mutex/`retryAfterSessionExpiry` pattern; rejects non-`ui://`). |
| `mcp/McpRegistry.kt` | `enabledTools()` stays complete (UI/settings need it). New `modelTools()` (visible to model). `descriptor()` keeps behaviour; new `modelDescriptor(name)`. New `suspend readUiResource(serverId, uri)` with small LRU (8 entries, keyed `serverId|uri`, cleared when the runtime is closed). New `suspend callToolForApp(serverId, remoteName, argsJson)` → requires installed runtime, tool enabled, `isAppVisible`; returns `ToolExecutionResult` through the shared `execute` result mapping (extract the mapping into a private `toExecutionResult(payload)` to avoid duplicating dedup/image logic). `execute()` result gains `uiResource` (Phase 2). |
| `tool/McpToolProvider.kt` | `definitions` uses `registry.modelTools()`; `handles` uses `modelDescriptor`. |
| `development/mcp.md` | Not yet; §9 added in Phase 3. |

Tests (`app/src/test/.../mcp/McpModelsTest.kt` extended, new `McpUiMetaTest.kt`): nested vs legacy key,
non-`ui://` ignored, visibility filtering/default, resource parse (text, blob, wrong mime, oversize, empty),
`modelTools` excludes app-only, `asToolDefinition` unchanged.

### Phase 2 — Durable reference

| File | Change |
|---|---|
| `tool/ToolProvider.kt` | `ToolExecutionResult` += `uiResource: McpUiReference? = null` (`serverId`, `resourceUri`). |
| `mcp/McpRegistry.kt` | `execute()` sets `uiResource` when the descriptor has `uiResourceUri` and the call is not an error. |
| `model/ChatMessage.kt` | `MessageSegment` += `toolUiServerId: String?`, `toolUiResourceUri: String?`; `ToolCallData` same two fields. Defaults null → old rows readable. |
| `viewmodel/GenerationToolBatchEffectExecutor.kt` | `GenerationToolOverlay.complete` copies the two fields to the segment and `ToolCallData`. |
| Round rebuild / persistence mappers | Grep `toolStructuredResult` and `structuredResult` across `viewmodel/` and `data/` (`GenerationToolRoundBuilder.kt`, `MessagePayloadBuilder.kt`, `NativeConversationGraphImporter.kt`, `DataExporter.kt`) and carry the two fields wherever `structuredResult` is carried. Provider projectors must not carry them. |

Tests: overlay `complete` round-trip; segment JSON old-row decode; export/import round-trip; provider projection
does not include UI fields.

### Phase 3 — Host rendering

Dependency: add `androidx-webkit` to `gradle/libs.versions.toml` and `app/build.gradle.kts`.

| File (new) | Role |
|---|---|
| `mcp/ui/McpAppBridgeRouter.kt` | **Pure** JSON-RPC router (no Android deps). Input: raw message string + `McpAppHostPort`. Output: response/notification strings. Handles `ui/initialize`, `ui/notifications/initialized`, `tools/call`, `resources/read`, `ui/open-link`, `ui/message`, `ui/notifications/size-changed`, `notifications/message`; everything else → `-32601`. Enforces: same `serverId`, tool app-visible, args are a JSON object, one in-flight call cap, 1 MiB message cap. |
| `mcp/ui/McpAppHostPort.kt` | Interface the router uses: `callTool`, `readResource`, `openLink`, `postDraft`, `onSize`. Implemented in UI layer over `McpRegistry`. Exists because the router must be unit-testable (§4.5 condition: real side-effect boundary). |
| `mcp/ui/McpAppSandbox.kt` | Pure builders: `buildCsp(meta)`, `buildPermissionsPolicy(meta)`, `appOrigin(serverId, uri)` = `https://<sha256-hex32>.mcp-app.invalid`, allowed-request predicate. |
| `ui/chat/message/McpAppView.kt` | Compose host. `AndroidView` + `WebView`, `DisposableEffect` destroy. Loads the page through `shouldInterceptRequest` on the synthetic origin so the **response carries real CSP / Permissions-Policy headers** (not `<meta>`). Blocks all other requests not allowed by CSP domains. `onCreateWindow` denied, navigation blocked, `allowFileAccess/ContentAccess=false`, mixed content never. Messaging via `WebViewCompat.addWebMessageListener` with `allowedOriginRules = {appOrigin}` (no `addJavascriptInterface`). |
| `ui/chat/message/ToolResultContent.kt` | In the `ToolKind.MCP` branch of `ToolDetailContent`, when `segment.toolUiResourceUri != null` and `WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)`: show an *Open interactive view* button; only then compose `McpAppView`. Text/structured result stays visible below. |
| `mcp/McpProtocolClient.kt` | `initialize` advertises `capabilities.extensions["io.modelcontextprotocol/ui"] = {mimeTypes:["text/html;profile=mcp-app"]}`. Verify exact key against the published spec before enabling. |
| `res/values*/strings.xml` (12 locales) | Button/labels; `development/settings-ui-ux.md` copy rules. |

Host→view messages: `ui/notifications/tool-input` (from `segment.toolArgs`, only if parseable object),
`ui/notifications/tool-result` (CallToolResult rebuilt from persisted fields), `host-context-changed` on
theme/size change. Out of scope v1: fullscreen/pip, `ui/download-file`, `ui/update-model-context`, sampling,
partial tool input, `domain` hint. `ui/message` only **prefills the composer draft**; it never sends.
`ui/open-link` is `https` only and asks the user to confirm.

Tests: `McpAppBridgeRouterTest` (pure: handshake order, server mismatch, app-only/model-only rules,
oversize, unknown method, concurrent cap), `McpAppSandboxTest` (CSP strings, origin determinism, allowed
request predicate), `ToolResultContentSourceContractTest` extended for the button gating.

### Phase 4 — Contract, docs, gate

- `development/mcp.md` §9 *MCP Apps* (ownership, invariants above, out-of-scope list), update status date.
- `development/README.md` registry row if scope text changes; `AGENTS.md` §5/§7.5 mention.
- `docs/en/mcp.md` (+ maintained translations) per `documentation-maintenance.md`.
- Full gate (§8.1 of `AGENTS.md`). Device validation is reported separately from build evidence.

## 3. Invariant check

| Invariant | How it holds |
|---|---|
| Single finalization owner / no parallel pipeline | Rendering is read-only over persisted segment; no new effect, command or state machine. |
| Room is durable truth | Only two optional strings added to existing segment JSON. |
| Demand-driven load | Resource read + WebView only after explicit tap. |
| No shared lock across network | Registry LRU uses a short `synchronized`; network outside lock; client mutex as today. |
| Fail closed | Every parse/validation failure degrades to the existing text rendering. |
| Source size ≤ 999 lines | New logic is split across router / sandbox / view files. |
| No weakened tests | New behaviour covered by pure-unit tests. |

## 4. Risks

- `androidx.webkit` feature availability varies by WebView version → hard gate, no fallback bridge.
- App HTML is untrusted JS: CSP must be an HTTP header (hence interception) and WebView must never have
  `addJavascriptInterface`.
- Tools returning UI-only content need `text` fallback; we keep showing the persisted text/structured result.
- Exact spec key names (`extensions` capability, protocol version string) must be re-verified at Phase 3 start.
