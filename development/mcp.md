# MCP Client Contract

Status: authoritative, 2026-09-20.

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
  header over plain HTTP is refused.
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
