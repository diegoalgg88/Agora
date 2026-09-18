# Model Context Protocol (MCP)

Agora can connect to remote MCP servers and expose their tools to supported chat models.

## Add a Server

1. Open **Settings → Tools → MCP**.
2. Tap **Add MCP Server**.
3. Choose **Streamable HTTP** or **SSE**.
4. Enter a display name and an http:// or https:// server URL.
5. Add only the custom HTTP headers required by the server, then save.

Streamable HTTP is the current transport. SSE is available for legacy MCP servers.

Custom header values use the same secret-setting storage as API keys: normally an Android Keystore AES-GCM envelope, with legacy/encryption-failure plaintext fallback. They can contain authorization tokens, so protect the device and exports and do not add headers the server does not require.

## Popular Servers

The **Popular Servers** section lists a curated catalog of well-known public MCP servers — documentation lookups (Context7, MDN, DeepWiki), web search (Parallel Search, Jina AI, Tavily), market and weather data (Yahoo Finance, CoinGecko, Open-Meteo, OpenWeather), flights (Kiwi.com), scam checks (Malwarebytes), diagrams (tldraw), domains (Find-A-Domain), and transit info (SubwayInfo NYC). Tap a server to add it with one action; the URL and any recommended default headers are filled in automatically.

Some entries require an API key (for example Jina AI, Tavily, SERPAPI, Composio, OpenWeather). Enter the key when prompted; it is stored with the same protections as other secrets. Servers you add from the catalog behave exactly like manually added servers: enable, disable, edit, or delete them at any time.

## Connection and Tool Discovery

An enabled server connects in the background. Its status is shown as **Idle**, **Connecting**, **Connected**, or **Connection error**. Use **Reconnect** from the server menu or editor to retry discovery.

After a successful connection, Agora lists the server's tools. Each discovered tool is enabled by default; open the server editor to disable individual tools. Disabling the server removes all of its tools from new model requests without deleting the configuration.

Enabled MCP tools join Agora's normal tool-calling pipeline. The model sees each tool's name, description, and input schema, and a tool result is stored with the conversation like other tool calls.

## Edit or Remove a Server

Tap a server to edit its transport, name, URL, custom headers, connection status, and enabled tools. Use the server's overflow menu to reconnect or delete it.

Deleting a server removes its configuration and makes its tools unavailable. Existing conversation history is retained.

## Security

An MCP server can cause external side effects through its tools. Only connect servers you trust, review their tool list, and grant the narrowest credentials possible. Prefer HTTPS outside a trusted local network.

## Troubleshooting

### Connection error

- Confirm the URL is reachable from the Android device.
- Verify that the selected transport matches the server.
- Check authentication headers and server logs.
- Use **Reconnect** after changing the server.

### No tools appear

- Confirm the server is enabled and reports **Connected**.
- Reconnect to refresh discovery.
- Open the editor and ensure the individual tools are enabled.

### A tool is not offered to the model

The active model/provider must support tool calling. Also verify that the server and the specific tool are both enabled.
