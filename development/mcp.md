# MCP Client Contract

Status: authoritative, 2026-10-06.

This contract owns Agora's Model Context Protocol client: server configuration and persistence
shape, transports, protocol negotiation, the process-wide registry supervisor, tool discovery and
execution, and the popular-server catalog. Agora is an MCP client only; it never hosts its own
local tools as an MCP server.

## 1. Terms and product boundary

- An MCP server is a user-configured remote endpoint (`McpServerConfig` in
  `data/SettingsContracts.kt`) with a URL, a transport (`streamable_http` default, legacy `sse`),
  optional custom headers, and a per-server set of disabled remote tool names.
- MCP tools are remote tools bridged into the ordinary generation pipeline through
  `tool/McpToolProvider.kt`. They must never gain a second generation, queue, Stop, settlement,
  or rendering pipeline.
- An MCP tool's public name is `mcp_<serverKey10>_<toolKey>_<sha256-3-bytes>`
  (`publicMcpToolName` in `mcp/McpModels.kt`): deterministic, bounded to 64 characters, and
  collision-resistant. `disabledTools` stores raw remote names; newly discovered tools are enabled
  by default.

## 2. Transports and protocol

- `mcp/McpClientTransport.kt` owns the transport boundary behind the `McpClientTransport`
  interface. `ensureReady` returns the active server session generation; a changed generation
  forces re-initialization before any other request.
- Streamable HTTP posts every JSON-RPC envelope to the configured endpoint, accepts a JSON or a
  finite SSE response body, tracks `Mcp-Session-Id`, and treats 404/410 as session expiry.
- Legacy SSE (protocol 2024-11-05) opens a long-lived GET stream, resolves the advertised
  `endpoint` event to a same-origin POST URL only (same scheme, host, port; no userinfo, no
  fragment), and routes asynchronous `message` responses by request id scoped to the connection
  generation.
- Protocol negotiation for Streamable HTTP descends through
  `2025-11-25 → 2025-06-18 → 2025-03-26 → 2024-11-05` starting at the transport's current version.
  Any failure on an intermediate candidate — version rejection or not — advances to the next
  candidate; only the last candidate's failure is terminal for the connection attempt. A
  server-negotiated version is accepted only when it belongs to the supported set.
- Requests are serialized per client (mutex in `mcp/McpProtocolClient.kt`): initialization and
  session replacement are observed atomically. `tools/call` uses the tool execution timeout; all
  other requests use the network tool timeout. `tools/list` pages by cursor with a 100-page cap.
- Session expiry (`McpSessionExpiredException`) retries the enclosing request at most twice
  after resetting the session.

## 3. Security boundary

- Custom headers are validated (`isValidMcpHeaderName/Value`, `MCP_RESERVED_HEADERS`): reserved
  header names — including `mcp-session-id`, `mcp-protocol-version`, and `content-type` — are
  rejected, duplicates collapse case-insensitively, and non-ASCII names or control-character
  values never reach the request.
- The shared cleartext-credential guard applies to every transport request: an Authorization-style
  header (or an OAuth Bearer token, section 10) over plain HTTP is refused for non-local hosts.
- Server URLs must be http or https with a host and no userinfo or fragment
  (`McpRegistry.normalizeEndpoint`).

## 4. Registry ownership

- `mcp/McpRegistry.kt` is the single process-wide connection authority. One `Runtime`
  (config + `McpProtocolClient` + jobs) per server; runtimes are siblings under the app scope's
  SupervisorJob so one broken endpoint cannot cancel another server or a generation.
- Runtime construction and replacement follow the build-ticket identity rules
  (`McpRuntimeBuildTicket`, `shouldStartMcpRuntimeBuild`, `isCurrentMcpRuntimeBuild`): every build
  carries a monotonic generation, and installation plus snapshot publication require the pending
  ticket, current configuration, enabled state, nonblank URL, and runtime identity to remain
  current. A stale build never installs over a newer or disabled configuration.
- Connect and tool-discovery work is capped by a two-permit semaphore
  (`MAX_CONCURRENT_CONNECTIONS`); the semaphore wraps only the network work, never backoff or UI
  collection. Failed connection attempts back off 5 s → 5 min (doubling) per server.
- The registry must not import Android UI types and must not run runtime work on the caller
  thread; page-entry refresh behavior is owned by `application-ui.md` §6.

## 5. Tool discovery and `tools/list_changed`

- The connected tool catalog is the immutable `McpServerSnapshot` list published to the
  `snapshots` StateFlow. Snapshot construction never blocks on I/O; consumers (`enabledTools`,
  `descriptor`) read the current value without locks on the network path.
- When a server sends `notifications/tools/list_changed` — inline in a Streamable HTTP response
  body or on the legacy SSE stream — the registry re-reads the remote tool list through the same
  discovery path as the connection loop and publishes a fresh CONNECTED snapshot with a new
  `lastSyncedAt`.
- The refresh is coalesced per runtime: an in-flight refresh absorbs later notifications.
  Ownership is re-checked before the network call and before publication, so a replaced or closed
  runtime never publishes. A failed refresh is logged and leaves the last complete snapshot
  intact; the connection loop's retry backoff remains the recovery path — the notification carries
  no retry contract from the server.
- Without a notification, the catalog refreshes only through the ordinary paths: reconcile,
  explicit refresh, page entry, or connection-loop retry.

## 6. Tool execution

- `McpRegistry.execute` resolves the public name to a descriptor of a currently installed
  runtime, requires a complete JSON object argument, and returns a `ToolExecutionResult` —
  never throws to the pipeline. Unknown or disabled tools, missing runtimes, malformed arguments,
  and remote failures are terminal error results.
- Response content supports text, base64 images (persisted via `ToolImageStore` as visual
  attachments), embedded image resources, `structuredContent`, and `isError`.
- When a text block duplicates the structured content's JSON tree, the duplicate is excluded from
  the detail display but never from the durable result.
- A successful call keeps the snapshot's tools and refreshes `lastSyncedAt`; a failed call marks
  the runtime ERROR and schedules its retry. Execution events emit `TargetResolved` (server name) →
  `Progress` → exactly one `Completed`.

## 7. Persistence and portability

- `mcpServers` is stored as JSON in DataStore (`SettingsManager`), exposed as a StateFlow by
  `SettingsRepository`, and merged by dedicated identity on import.
- Header values are credentials, and so are credential query parameters on the URL (for example
  `tavilyApiKey`). Exports strip both (`McpServerConfig.withoutSecrets`); they travel only in the
  explicit secret category of the archive. The archive boundary itself is owned by
  `import-export.md`.
- The catalog in `mcp/PopularMcpServers.kt` is a curated list of free remote endpoints. Catalog
  defaults may merge into saved configs (`applyPopularDefaultHeaders`) without overwriting a
  user-set header (case-insensitive). Catalog entries that require credentials prefill the editor
  with their auth header; entries whose key is optional stay one-tap and let the user add the
  header manually.

## 8. Change discipline

- A new transport, protocol version, or negotiation rule changes only
  `McpClientTransport.kt`/`McpProtocolClient.kt` and their tests; it must not fork the registry or
  the tool provider.
- New tool-payload content kinds extend `parseCallPayload` and the shared dedup rule; they must not
  add provider-specific rendering paths outside `message-generation.md`'s tool-detail contract.
- A behavior change in this contract updates `docs/en/mcp.md` (and maintained translations)
  together with the code, per `documentation-maintenance.md`.

## 9. MCP Apps (SEP-1865, spec revision 2026-01-26)

An MCP App is a *view of a completed tool result*. It adds no generation, queue, Stop, settlement,
context or branch behavior: the tool call still runs through `McpToolProvider` →
`GenerationToolExecutor` → `GenerationToolOverlay.complete`, and the view only reads what that
path already persisted.

### 9.1 Negotiation and discovery

- `initialize` advertises `capabilities.extensions["io.modelcontextprotocol/ui"].mimeTypes =
  ["text/html;profile=mcp-app"]` (`mcpClientCapabilities`). A server that ignores it stays a plain
  tool server.
- `tools/list` is parsed by `parseMcpToolUiMeta`: `_meta.ui.resourceUri` wins over the deprecated flat
  `_meta["ui/resourceUri"]`; only `ui://` is accepted; `visibility` keeps only `model`/`app` and falls
  back to both when empty or absent.
- Visibility is enforced in the registry, not in the UI: tools without the `model` audience are
  excluded from `McpRegistry.modelTools()` / `modelDescriptor()`, so they are never offered to a
  Provider, never authorized for a tool round, and `execute` refuses them. A view reaches a tool only
  through `McpRegistry.callToolForApp(serverId, remoteName, args)`, which requires the same server,
  CONNECTED status, an enabled tool and the `app` audience.
- `McpRegistry.readUiResource` (`resources/read`) accepts only `ui://` and validates through
  `parseMcpUiResourceContents`: entry URI equal to the request, MIME `text/html;profile=mcp-app`
  (whitespace/case-insensitive), `text` or base64 `blob`, non-blank, at most 2 MiB. Any failure is a
  thrown error and the plain result remains the only presentation. Documents are cached in a bounded
  access-ordered map (8 entries) guarded by the registry lock; the cache is cleared when the server's
  runtime is replaced, closed, or its tool list is refreshed. No network call runs under that lock.

### 9.2 Durable reference

- A successful call of a tool that declares `resourceUri` returns `ToolExecutionResult.uiResource`
  (`McpUiReference(serverId, resourceUri)`); error results never carry one.
- `GenerationToolOverlay.complete` copies it to `MessageSegment.toolUiServerId` /
  `toolUiResourceUri` and `ToolCallData.uiServerId` / `uiResourceUri`;
  `GenerationToolRoundBuilder` preserves it when a round is rebuilt. The fields live in the existing
  segment JSON (no Room migration; old rows decode with nulls).
- The pointer is UI-only. Provider projection (`MessagePayloadBuilder`, `ApiPathAssembler`,
  `ProviderMessageProjector`) must never read it, and the HTML is never persisted.
- The `tool-input` / `tool-result` sent to a view are rebuilt from persisted segment fields
  (`toolArgs`, `toolResultText ?: toolResult`, `toolStructuredResult`). Tool images are not forwarded
  in v1.

### 9.2b Resources (display-only)

- `McpProtocolClient.listResources()` pages through `resources/list` (at most 20 pages, 500 resources) and
  `McpRegistry.refreshToolsSnapshot` publishes the result as `McpServerSnapshot.resources`, in the same
  snapshot as the tools so the two never disagree.
- A server is asked only if its `initialize` result declares the `resources` capability (a result with no
  capabilities object at all is tried). Any non-cancellation failure of `resources/list` degrades to an
  empty list and never fails the tool refresh or marks the server ERROR.
- Resources are display-only: the MCP settings page lists them per server (name, URI, description; `ui://`
  documents get their own icon, at most 50 rows with a "+N more" row), and tools that declare a `ui://`
  document are labelled there. Nothing in this section is offered to a model, read automatically, or
  added to a Provider request. Exposing resources to the model is a separate decision that needs its own
  authorization and context-budget design.

### 9.3 Rendering and isolation

- Rendering is demand-driven: `McpAppEntry` shows an *Open interactive view* control only for a
  SUCCEEDED segment that carries the pointer. It is rendered in two surfaces: the MCP branch of
  `ToolDetailContent` (detail sheet) and, inline in the chat timeline, directly under each tool block's
  card (`TimelineMcpAppEntries`, no group expansion needed). Both reuse the same composable; no resource
  is read and no WebView exists until the view opens; closing it or leaving the composition destroys the
  WebView. Open state is not saved across list recycling, so scrolling back never recreates a WebView.
- Auto-open applies only to the actively streaming run's freshest UI result: the view opens once, at a
  fixed short height (240 dp) until the user taps *Expand* (then the app-driven 120-800 dp height
  applies), because ephemeral server-side data (e.g. a map `viz_id`) is freshest right after the tool
  round. At most one view auto-opens per message; a newer UI result closes an untouched older one. Every
  other surface (history, older results, detail sheet) stays demand-driven.
- A view is rendered only when WebView supports `WEB_MESSAGE_LISTENER` and `DOCUMENT_START_SCRIPT`
  (`McpAppWebSupport`). There is deliberately no weaker fallback bridge.
- `McpAppWebSession` owns one WebView per document: the page is served by `shouldInterceptRequest` on a
  dedicated synthetic origin (`McpAppSandbox.origin`, SHA-256 of server and URI) with real
  `Content-Security-Policy` and `Permissions-Policy` response headers; every other request is blocked
  unless `McpAppSandbox.isRequestAllowed` finds the origin declared by the server (`data:` is allowed
  because the CSP confines it to image/font/media). There is no `addJavascriptInterface`; messages use an
  origin-restricted `WebMessageListener` plus a document-start shim that stands in for
  `window.parent`. Navigation, popups, JS dialogs, file/content access, geolocation and every device
  permission are refused; DOM storage is off.
- `McpAppSandbox.buildCsp` follows the spec's construction, plus `worker-src 'self' blob:` and `blob:` for
  images, fonts and media (WebGL map/3D libraries such as MapLibre cannot start without a blob worker; the
  MCP Apps reference host allows the same). No external origin is added.
- The page-side bridge is `McpAppBridgeShim`. Host-to-view messages are re-dispatched as `message` events;
  `MessageEventInit.source` rejects anything but a Window/MessagePort/ServiceWorker, so the stand-in parent
  is attached to the event instance afterwards instead of passed to the constructor. Blocked sub-requests
  and the view's console output go to `DebugLog` (scheme, host and path only), never to the UI.
- Declared domains are validated as bare `https`/`wss` origins (optional `*.` prefix and port),
  deduplicated and capped at 16 per directive before they reach a header, so a server cannot inject a
  directive or widen the policy. `connectDomains` only reach `connect-src`; `resourceDomains` reach
  script/style/img/font/media/worker.
  Permissions are never granted in v1.

### 9.4 Bridge router (`McpAppBridgeRouter`)

- Pure JSON-RPC 2.0 router, no Android/Room/Provider/UI dependency; it maps one raw view message to
  zero or more raw host messages and is the only place that decides what a view may do. Messages over
  1 MiB, malformed JSON, responses and `id: null` requests are dropped.
- Handshake order is enforced: `ui/initialize` → `ui/notifications/initialized`; the host sends nothing
  before `initialized`, then `tool-input` precedes `tool-result`. Before the handshake completes
  every request except `ping`/`ui/initialize` gets `-32002`.
- Supported: `tools/call` (cap of 4 in flight, `-32602` on invalid params, thrown port failures become a
  bounded `-32000`; tool errors are `isError` results), `ui/open-link` (`https` only, no credentials,
  at most 2048 chars, always user-confirmed), `ui/request-display-mode` (always `inline`), `ping`,
  `ui/notifications/size-changed` (height clamped to 0..100000 CSS px, then to 120..800 dp by the host),
  `notifications/message` (bounded debug log).
- Refused in v1: `ui/message` and `ui/update-model-context` (`-32000`; a view can never write into the
  conversation or model context), `resources/read` and every unknown method (`-32601`).
  `serverResources` is therefore not advertised.
- Host-initiated `ui/resource-teardown` is sent best-effort on close; the WebView is destroyed
  without waiting for the reply.

### 9.5 Out of scope for v1

Fullscreen/picture-in-picture, downloads, sampling, model-context updates, `ui/message`, partial tool
input, the `domain` hint, view-initiated `resources/read`, device permissions and forwarding tool
images to the view. Adding any of them changes this section and `McpAppBridgeRouter` only; it must not
add a path from a view into the generation pipeline.

### 9.6 Tests

Pure-unit coverage lives in `McpUiMetaTest` (metadata, visibility, resource validation),
`McpAppSandboxTest` (origin, CSP, request allow-list), `McpAppBridgeRouterTest` (handshake, limits,
refusals, URL policy), `McpAppBridgeShimTest` (the `MessageEvent.source` rule), `McpResourcesTest`
(`resources/list` parsing, capability gate), `GenerationToolUiReferenceTest` and `MessageSegmentUiReferenceTest` (durable
pointer). WebView isolation and the `window.parent` shim require device validation and are not claimed
by compilation alone.

## 10. OAuth (MCP authorization)

Agora signs in to protected remote servers with the MCP authorization flow: authorization code +
PKCE `S256`, RFC 9728 Protected Resource Metadata, RFC 8414 / OIDC discovery, RFC 7591 dynamic client
registration, and the RFC 8707 `resource` parameter. Code lives in `mcp/oauth/`; the transports only
consume an `McpAuthProvider`.

### 10.1 Ownership

- `McpOAuthManager` (owned by `McpRegistry.oauth`) is the only component that talks to authorization
  servers, reads or writes credentials, and runs the interactive flow. It never owns connection state:
  it reports `onResult(serverId, error?)` and the registry decides (`refresh(serverId)` on success;
  an `authRequired` ERROR snapshot on failure).
- `McpOAuthClient` is stateless network I/O (discovery, registration, code exchange, refresh) with
  bounded timeouts and 256 KiB response bodies. `McpOAuthProtocol.kt` holds the pure rules (challenge
  parsing, candidate URLs, PKCE, redirect parsing, metadata validation) and is unit-tested without Android.
- A runtime gets an auth provider only when the user configured no `Authorization` header of their own
  (`McpRegistry.authFor`); static headers always win and OAuth stays inert.

### 10.2 Transport behavior

- Both transports add `Authorization: Bearer` from `McpAuthProvider.authorizationHeader()` before every
  request (the legacy SSE GET stream included). A 401 — or a 403 `insufficient_scope` — carrying a
  `WWW-Authenticate: Bearer` challenge becomes `McpAuthRequiredException`; any other 401 stays a plain
  HTTP error. `onRejected` runs at most once per request (refresh, or "another request already
  refreshed"); a second rejection propagates.
- `McpAuthRequiredException` is never swallowed by protocol-version negotiation. The registry marks the
  snapshot `ERROR` with `authRequired = true` and stops the backoff loop: only sign-in, sign-out,
  Reconnect or page-entry refresh start a new attempt.
- A Bearer token over cleartext HTTP is refused for non-local hosts by the shared credential guard.

### 10.3 Flow and security rules

- Discovery order: `resource_metadata` from the challenge, then `/.well-known/oauth-protected-resource`
  (path-specific first), then the authorization server's RFC 8414 and OIDC documents. Servers without
  any metadata fall back to `/authorize`, `/token`, `/register` on their origin only after a challenge
  was observed. Advertised `resource` must cover the server URL; `issuer` must match the requested
  issuer; every OAuth endpoint must be https (http only for local/LAN hosts); PKCE `S256` must be
  advertised (metadata-less legacy defaults are exempt).
- The resource identifier is the canonical server URL (lowercase scheme/host, default port and trailing
  slash dropped, query and fragment removed — query parameters may carry API keys).
- The redirect is the private-use URI `com.newoether.agora:/oauth/mcp`, received by
  `McpOAuthCallbackActivity`, which only forwards it. The manager requires exact scheme and path, a
  known single-use `state` (10-minute TTL, one pending request per server, consumed before anything
  else can fail), a matching RFC 9207 `iss` when present or announced, an unchanged server URL, and
  the PKCE verifier.
- Client resolution: configured `oauthClientId` (public client) → previously registered client →
  dynamic registration. A server with none of them reports that a Client ID is required.
- Refresh is serialized per server and compare-and-set against the stored refresh token, so a
  concurrent sign-out or re-sign-in is never overwritten. `invalid_grant` clears the tokens (client
  registration is kept); a transient network error propagates and the connection loop backs off.

### 10.4 Persistence and portability

- Tokens, client registrations and pending requests are one `SecretCrypto`-encrypted DataStore value
  (`MCP_OAUTH_JSON`, `McpOAuthStore`), written only through `DataStore.edit`. They are device-local:
  never part of the portable settings allowlist, native backup or the secrets category. An unreadable
  value fails closed to "signed out".
- `McpServerConfig` carries only `oauthClientId` and `oauthScope` (not secrets). Tokens are kept out
  of the config on purpose: a token refresh must never change the config that gates runtime rebuilds.
- Credentials are tied to the canonical resource; changing the server URL makes them unusable.
  They are deleted when a server with a live runtime disappears from settings (`McpRegistry.reconcile`).
  The startup empty emission has no runtimes and therefore never deletes anything.

### 10.5 Tests

`McpOAuthProtocolTest` covers challenge parsing, canonical resource, candidate URLs, the RFC 7636
vector, authorization URL, redirect acceptance, metadata validation and token expiry. Browser
hand-off, the redirect activity and real authorization servers require device validation and are not
claimed by compilation alone.
