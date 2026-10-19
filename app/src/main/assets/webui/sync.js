// The browser's runtime client over /api/sync. Draft settlement comes from the phone's composer.
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import { sessionSignedIn } from "./api.js";
import { t } from "./i18n.js";

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
  connected: false,
  composer: null,
  text: "",
  pendingAction: 0,
  snackbar: null,
  scrollRequest: null,
};
const bodySizes = new Map();
let watched = new Set();
let socket = null;
let retryMs = 1_000;
let onSessionEnded = () => {};
let running = false;
let retryTimer = 0;
let openSeq = 0;
let editRevision = 0;
let nextAction = 0;
let noticeId = 0;

function update(patch) {
  state = { ...state, ...patch };
  listeners.forEach((listener) => listener(state));
}

function send(command) {
  if (socket?.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(command));
  return true;
}

function select(conversationId, patch = {}) {
  watched = new Set();
  bodySizes.clear();
  update({ openId: conversationId, openStatus: conversationId ? "loading" : null,
    path: [], generating: false, streaming: null, bodies: new Map(), scrollRequest: null, ...patch });
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
    case "scroll_to_bottom":
      if (event.conversationId === state.openId && event.seq === openSeq) update({ scrollRequest: event });
      break;
    case "opened":
      if (event.seq === openSeq) select(event.conversationId ?? null, { composer: null });
      break;
    case "composer":
      if ((event.conversationId ?? null) !== state.openId || event.seq !== openSeq) return;
      update({ composer: event, generating: event.generating,
        text: event.editRevision >= editRevision ? event.text : state.text,
        pendingAction: event.actionId >= state.pendingAction ? 0 : state.pendingAction });
      break;
    case "snackbar":
      update({ snackbar: { id: ++noticeId, message: event.message } });
      break;
    case "conversations":
      update({ conversations: event.items });
      break;
    case "display":
      update({ display: event });
      break;
    case "path":
      if (event.conversationId !== state.openId) return;
      if (state.streaming && !event.messages.some((message) => message.id === state.streaming.id)) {
        update({ path: event.messages, generating: event.generating, streaming: null, openStatus: "ready" });
      } else {
        update({ path: event.messages, generating: event.generating, openStatus: "ready" });
      }
      break;
    case "payload": {
      if (event.conversationId !== state.openId) return;
      const bodies = new Map(state.bodies);
      bodies.delete(event.message.id); // Re-insert so the newest body is evicted last.
      bodies.set(event.message.id, event.message);
      bodySizes.set(event.message.id, size * 2);
      evict(bodies);
      if (state.streaming?.id === event.message.id &&
          ["SUCCESS", "STOPPED", "ERROR"].includes(event.message.status)) {
        update({ bodies, streaming: null });
      } else {
        update({ bodies });
      }
      break;
    }
    case "streaming":
      if (event.conversationId !== state.openId) return;
      if (event.message &&
          !["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(event.message.id)?.status)) {
        update({ streaming: event.message });
      } else if (!event.message && state.streaming &&
          ["SUCCESS", "STOPPED", "ERROR"].includes(state.bodies.get(state.streaming.id)?.status)) {
        update({ streaming: null });
      } else if (!state.streaming) {
        update({ streaming: null });
      }
      // Otherwise retain the last frame until the watched terminal row arrives.
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
    if (socket !== ws || !running) return;
    retryMs = 1_000;
    update({ connected: true, composer: null, pendingAction: 0 });
    send({ type: "open", conversationId: state.openId, seq: openSeq });
    // Reconnection restores input only, never Send or Stop.
    editRevision = 0;
    if (state.text) send({ type: "draft", text: state.text, revision: ++editRevision, seq: openSeq });
    if (watched.size) send({ type: "watch", messageIds: [...watched] });
  };
  ws.onmessage = (message) => {
    if (socket === ws && running) receive(JSON.parse(message.data), message.data.length);
  };
  ws.onclose = async (close) => {
    if (socket !== ws) return;
    socket = null;
    update({ connected: false, composer: null, pendingAction: 0,
      snackbar: state.pendingAction ? { id: ++noticeId, message: t.sendUnconfirmed } : state.snackbar });
    // A refused upgrade also arrives here, so the session decides between retrying and signing out.
    const signedIn = close.code !== CLOSE_VIOLATED_POLICY && (await sessionSignedIn().catch(() => true));
    if (!running) return;
    if (!signedIn) {
      onSessionEnded();
      return;
    }
    retryTimer = setTimeout(() => { if (running && !socket) connect(); }, retryMs);
    retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
  };
}

export const sync = {
  start(sessionEnded) {
    running = true;
    onSessionEnded = sessionEnded;
    if (!socket) connect();
  },
  stop() {
    running = false;
    clearTimeout(retryTimer);
    const ws = socket;
    socket = null;
    ws?.close();
    editRevision = 0;
    openSeq = 0;
    select(null, { connected: false, composer: null, text: "", pendingAction: 0, snackbar: null });
  },
  open(conversationId) {
    if (!state.connected || conversationId === state.openId) return;
    openSeq++;
    editRevision = 0;
    select(conversationId, { composer: null, text: "", pendingAction: 0 });
    send({ type: "open", conversationId, seq: openSeq });
  },
  edit(text) {
    update({ text });
    send({ type: "draft", text, revision: ++editRevision, seq: openSeq });
  },
  submit() {
    if (!state.connected || !state.composer || state.pendingAction) return;
    const phase = state.composer.phase;
    if (phase !== "IDLE" && phase !== "WAITING") return;
    const command = { type: phase === "WAITING" ? "cancel_waiting" : "send",
      seq: openSeq, actionId: ++nextAction, text: state.text };
    if (send(command)) update({ pendingAction: command.actionId });
  },
  stopGeneration() {
    if (state.connected && state.composer && !state.composer.stopping) send({ type: "stop", seq: openSeq });
  },
  dismissSnackbar(id) {
    if (state.snackbar?.id === id) update({ snackbar: null });
  },
  consumeScroll(request) {
    if (state.scrollRequest === request) update({ scrollRequest: null });
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
