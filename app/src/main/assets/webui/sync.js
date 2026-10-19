// Read-only mirror of the phone's chats over /api/sync. The phone pushes state; the browser only
// chooses which conversation is open and which rows it is looking at.
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import { sessionSignedIn } from "./api.js";

// The app's ConversationMessagePayloadCache bounds: rows kept after they leave the screen.
const CACHE_MAX_ENTRIES = 16;
const CACHE_MAX_BYTES = 8 * 1024 * 1024;
const RETRY_MAX_MS = 10_000;
// WebSocket close code the server uses when the browser session ends.
const CLOSE_VIOLATED_POLICY = 1008;

const listeners = new Set();
let state = {
  conversations: [],
  display: null,
  openId: null,
  /** "loading" | "ready" | "deleted" | "failed" while a conversation is open. */
  openStatus: null,
  path: [],
  generating: false,
  streaming: null,
  bodies: new Map(),
};
const bodySizes = new Map();
let watched = new Set();
let socket = null;
let retryMs = 1_000;
let onSessionEnded = () => {};

function update(patch) {
  state = { ...state, ...patch };
  listeners.forEach((listener) => listener(state));
}

function send(command) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(command));
}

/** Drops rows that are off screen once there are too many or they are too large. */
function evict(bodies) {
  let bytes = 0;
  const idle = [];
  for (const id of bodies.keys()) {
    if (watched.has(id)) continue;
    idle.push(id);
    bytes += bodySizes.get(id) ?? 0;
  }
  while (idle.length > CACHE_MAX_ENTRIES || (bytes > CACHE_MAX_BYTES && idle.length > 0)) {
    const id = idle.shift();
    bytes -= bodySizes.get(id) ?? 0;
    bodies.delete(id);
    bodySizes.delete(id);
  }
}

function receive(event, size) {
  switch (event.type) {
    case "conversations":
      update({ conversations: event.items });
      break;
    case "display":
      update({ display: event });
      break;
    case "path":
      if (event.conversationId !== state.openId) return;
      update({ path: event.messages, generating: event.generating, openStatus: "ready" });
      break;
    case "payload": {
      if (event.conversationId !== state.openId) return;
      const bodies = new Map(state.bodies);
      bodies.delete(event.message.id); // Re-insert so the newest body is evicted last.
      bodies.set(event.message.id, event.message);
      bodySizes.set(event.message.id, size * 2);
      evict(bodies);
      update({ bodies });
      break;
    }
    case "streaming":
      if (event.conversationId === state.openId) update({ streaming: event.message ?? null });
      break;
    case "deleted":
      if (event.conversationId === state.openId) update({ openStatus: "deleted" });
      break;
    case "load_failed":
      if (event.conversationId === state.openId) update({ openStatus: "failed" });
      break;
  }
}

function connect() {
  const scheme = location.protocol === "https:" ? "wss:" : "ws:";
  const ws = new WebSocket(`${scheme}//${location.host}/api/sync`);
  socket = ws;
  ws.onopen = () => {
    retryMs = 1_000;
    if (state.openId) send({ type: "open", conversationId: state.openId });
    if (watched.size) send({ type: "watch", messageIds: [...watched] });
  };
  ws.onmessage = (message) => receive(JSON.parse(message.data), message.data.length);
  ws.onclose = async (close) => {
    if (socket !== ws) return;
    socket = null;
    // A refused upgrade also arrives here, so the session decides between retrying and signing out.
    const signedIn = close.code !== CLOSE_VIOLATED_POLICY && (await sessionSignedIn().catch(() => true));
    if (!signedIn) {
      onSessionEnded();
      return;
    }
    setTimeout(() => { if (!socket) connect(); }, retryMs);
    retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
  };
}

export const sync = {
  start(sessionEnded) {
    onSessionEnded = sessionEnded;
    if (!socket) connect();
  },
  stop() {
    const ws = socket;
    socket = null;
    ws?.close();
  },
  open(conversationId) {
    if (conversationId === state.openId) return;
    watched = new Set();
    bodySizes.clear();
    update({
      openId: conversationId,
      openStatus: conversationId ? "loading" : null,
      path: [],
      generating: false,
      streaming: null,
      bodies: new Map(),
    });
    send({ type: "open", conversationId });
  },
  /** Rows on screen; the server sends and keeps their bodies current. */
  watch(ids) {
    const next = new Set(ids);
    if (next.size === watched.size && [...next].every((id) => watched.has(id))) return;
    watched = next;
    send({ type: "watch", messageIds: [...next] });
  },
};

export function useSync() {
  const [snapshot, setSnapshot] = useState(state);
  useEffect(() => {
    listeners.add(setSnapshot);
    setSnapshot(state);
    return () => listeners.delete(setSnapshot);
  }, []);
  return snapshot;
}
