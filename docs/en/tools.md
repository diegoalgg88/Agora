# Agentic Tools

Agora can let a model call tools across multiple generation passes.

## Available capabilities

Depending on settings, build, and provider support, tools include:

- web search
- image generation
- active and saved memory
- past-conversation search
- MCP servers
- Skills
- Tasks and Loops automation
- remote Conch/SSH commands, durable job management, file operations, and `view_image`
- the F-Droid Alpine sandbox
- assistant device actions: alarms, calendar events, opening files and links, IP-based location, local time, and device notifications

Open **Settings → Tools** to review the tool surfaces: the unified **Tools** page (assistant device actions), **Web Search**, **Conversation Search**, **Shell**, **MCP**, and **Automation**.

## Assistant device tools

Open **Settings → Tools → Tools** to enable individual device actions. Each tool is off by default:

- **Set alarm** — stage an alarm or countdown timer in the device clock app.
- **Open file** — open a sandbox file with the default Android app.
- **Calendar** — create, list, update, and delete events on the device calendar (the calendar tools request the Android calendar permissions when first enabled).
- **Get location** — approximate city-level location estimated from the device IP via the ipwho.is service.
- **Get local time** — current date, time, and timezone.
- **Open URL** — open a link in the browser or default app.
- **Send notification** — post a notification on the device.

!!! warning "Destructive actions require your approval"
    Setting an alarm and creating, updating, or deleting a calendar event are **staged** first: the model never executes them directly. A banner appears in the conversation with the action summary, and only the **Approve** button you tap performs the change on the device. **Discard** removes the staged action.

Read-only actions (opening a URL, listing events, location, time, notifications) run directly, but a tool still only receives the arguments needed for its call.

## Permissions and defaults

Web search, active memory, saved memory, past-conversation access, and the global shell permission are enabled by default. Shell calls still require a configured device, and its confirmation policy remains authoritative. Automation tools and all assistant device tools are disabled by default. MCP availability is controlled per server and per tool.

Tool calls and results become part of the conversation protocol and can be sent to the selected model on subsequent passes. External tools receive the arguments needed for their call. Review each server and permission before enabling it.

Image-generation and embedding credentials come from their selected providers; they are not separate universal tool secrets. See [MCP](mcp.md), [Automation](automation.md), [Shell](shell.md), [System Assistant](assistant.md), and [Privacy & Security](privacy.md).
