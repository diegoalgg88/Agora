import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_CALL_SPLIT, ICON_EXPAND_ALL, ICON_LOGOUT,
  ICON_MENU, ICON_MORE_VERT, ICON_PSYCHOLOGY, ICON_REPEAT, ICON_SEARCH, ICON_SHARE, ICON_STOP,
} from "./icons.js";
import { postJson } from "./api.js";
import { sync, useSync } from "./sync.js";
import { MessageList } from "./messages.js";
/*
 * Chat frame, mirroring the app's chat screen (ChatTopBar, ChatDrawerContent, ChatBottomBar).
 * Controls the browser cannot use yet are shown as in the app but disabled.
 */
// ChatDrawerHost.usesSideBySideDrawer: wider than DRAWER_MAX_WIDTH (360) + CHAT_APP_WIDTH_THRESHOLD (600).
const SIDE_BY_SIDE_QUERY = "(min-width: 960.02px)";

function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);
  return matches;
}

function DrawerButton({ className, iconPath, label, onClick, disabled = true }) {
  return html`
    <button class=${`drawer-button ${className}`} type="button" disabled=${disabled} onClick=${onClick}>
      ${icon(iconPath)}<span>${label}</span>
    </button>`;
}

/**
 * A drawer row: 44 dp with 2 dp above and below, a capsule highlight on secondaryContainer when
 * selected, the title in bodyLarge, and an 18 dp slot for the generating spinner or unread dot.
 */
function ConversationRow({ conversation, selected, onSelect }) {
  // resolveDrawerConversationIndicator: generating first; unread only when not selected.
  const indicator = conversation.generating ? "generating"
    : conversation.unread && !selected ? "unread" : null;
  return html`
    <button class=${selected ? "conversation-row selected" : "conversation-row"} type="button"
      role="listitem" aria-current=${selected ? "true" : null} onClick=${() => onSelect(conversation.id)}>
      <span class="conversation-title">${conversation.title}</span>
      <span class="conversation-indicator">
        ${indicator === "generating" && html`<span class="spinner" aria-hidden="true"></span>`}
        ${indicator === "unread" && html`<span class="unread-dot" role="img" aria-label=${t.unreadGeneration}></span>`}
      </span>
    </button>`;
}

/** ChatDrawerContent: title, search, Tasks, New Chat, then the conversation list. */
function DrawerContent({ conversations, openId, onSelect, connected }) {
  return html`
    <h2 class="drawer-title">${t.conversations}</h2>
    <div class="drawer-search">
      ${icon(ICON_SEARCH)}
      <input type="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled />
    </div>
    <${DrawerButton} className="tonal tasks" iconPath=${ICON_REPEAT} label=${t.tasks} />
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat}
      disabled=${!connected} onClick=${() => onSelect(null)} />
    <div class="drawer-list" role="list" aria-label=${t.conversations}>
      ${conversations.map((conversation) => html`
        <${ConversationRow} key=${conversation.id} conversation=${conversation}
          selected=${conversation.id === openId} onSelect=${onSelect} />`)}
    </div>`;
}

/** AgoraDropdownMenu: 24 dp corners, 48 dp items with an inset capsule highlight. */
function MoreMenu({ expanded, reduceMotion, anchor, onSignOut, onClose, onExited }) {
  const menu = useRef(null);
  const motion = useRef({ scale: 0.8, alpha: 0, scaleVelocity: 0, alphaVelocity: 0 });
  useLayoutEffect(() => {
    const node = menu.current;
    const measure = () => {
      const bounds = anchor.current.getBoundingClientRect();
      const left = bounds.right - node.offsetWidth;
      const top = bounds.bottom + 4;
      node.style.left = `${left}px`;
      node.style.top = `${top}px`;
      const pivotX = bounds.left >= left + node.offsetWidth ? 1 : bounds.right <= left ? 0
        : ((Math.max(bounds.left, left) + Math.min(bounds.right, left + node.offsetWidth)) / 2 - left) / node.offsetWidth;
      const pivotY = top >= bounds.bottom ? 0 : top + node.offsetHeight <= bounds.top ? 1
        : ((Math.max(bounds.top, top) + Math.min(bounds.bottom, top + node.offsetHeight)) / 2 - top) / node.offsetHeight;
      node.style.transformOrigin = `${pivotX * 100}% ${pivotY * 100}%`;
    };
    const geometry = new ResizeObserver(measure);
    [node, anchor.current].forEach((element) => geometry.observe(element, { box: "border-box" }));
    window.addEventListener("resize", measure);
    measure();
    node.querySelector("[role=menuitem]:not([disabled])")?.focus();
    return () => { geometry.disconnect(); window.removeEventListener("resize", measure); };
  }, []);
  useLayoutEffect(() => {
    const node = menu.current;
    const values = motion.current;
    let frame = 0;
    const started = performance.now();
    const samples = ["scale", "alpha"].map((key) => {
      const target = key === "scale" ? expanded ? 1 : 0.8 : expanded ? 1 : 0;
      const omega = Math.sqrt(key === "scale" ? 1400 : 3800);
      const damping = key === "scale" ? Math.fround(0.9) : 1;
      const displacement = values[key] - target;
      const velocity = values[`${key}Velocity`];
      // Compose estimates the final visibility-threshold crossing, not instantaneous speed.
      const position = Math.abs(Math.fround(displacement / 0.01));
      const speed = Math.fround(velocity / 0.01) * (displacement < 0 ? -1 : 1);
      const root = -damping * omega;
      const frequency = omega * Math.sqrt(1 - damping * damping);
      let duration = 0;
      if (position !== 0 || speed !== 0) {
        if (damping < 1) {
          const coefficient = (speed - root * position) / frequency;
          duration = Math.log(1 / Math.hypot(position, coefficient)) / root;
        } else {
          const coefficient = speed - root * position;
          const first = Math.log(Math.abs(1 / position)) / root;
          const guess = Math.log(Math.abs(1 / coefficient));
          let second = guess;
          for (let i = 0; i < 6; i++) second = guess - Math.log(Math.abs(second / root));
          second /= root;
          duration = !Number.isFinite(first) ? second : !Number.isFinite(second) ? first : Math.max(first, second);
          const inflection = -(root * position + coefficient) / (root * coefficient);
          const extremum = (position + coefficient * inflection) * Math.exp(root * inflection);
          let delta = -1;
          if (inflection > 0 && -extremum >= 1) {
            duration = -2 / root - position / coefficient;
            delta = 1;
          } else if (inflection > 0 && coefficient < 0 && position > 0) duration = 0;
          for (let i = 0; i < 100; i++) {
            const before = duration;
            const decay = Math.exp(root * duration);
            duration -= ((position + coefficient * duration) * decay + delta) /
              ((coefficient * (root * duration + 1) + position * root) * decay);
            if (Math.abs(before - duration) <= 0.001) break;
          }
        }
      }
      return { key, target, omega, damping, displacement, velocity,
        duration: Math.max(0, Math.trunc(duration * 1000)) };
    });
    const advance = (time) => {
      const elapsedMs = Math.max(0, Math.trunc(time - started));
      const elapsed = elapsedMs / 1000;
      // Material3 1.4.0 Standard FastSpatial / FastEffects, sampled with retained velocity.
      for (const { key, target, omega, damping, displacement, velocity, duration } of samples) {
        const speedKey = `${key}Velocity`;
        if ((key === "scale" && reduceMotion) || elapsedMs >= duration) {
          values[key] = target;
          values[speedKey] = 0;
          continue;
        }
        const decay = Math.exp(-damping * omega * elapsed);
        if (damping === 1) {
          const coefficient = velocity + omega * displacement;
          values[key] = target + decay * (displacement + coefficient * elapsed);
          values[speedKey] = decay * (coefficient - omega * (displacement + coefficient * elapsed));
        } else {
          const frequency = omega * Math.sqrt(1 - damping * damping);
          const coefficient = (velocity + damping * omega * displacement) / frequency;
          const cosine = Math.cos(frequency * elapsed);
          const sine = Math.sin(frequency * elapsed);
          const position = displacement * cosine + coefficient * sine;
          values[key] = target + decay * position;
          values[speedKey] = decay * (-damping * omega * position + frequency * (coefficient * cosine - displacement * sine));
        }
      }
      node.style.transform = `scale(${values.scale})`;
      node.style.opacity = String(values.alpha);
    };
    const tick = (time) => {
      advance(time);
      if (values.scaleVelocity !== 0 || values.alphaVelocity !== 0 ||
          values.scale !== (expanded ? 1 : 0.8) || values.alpha !== (expanded ? 1 : 0)) {
        frame = requestAnimationFrame(tick);
      } else if (!expanded) onExited(node.contains(document.activeElement));
    };
    tick(started);
    return () => { cancelAnimationFrame(frame); advance(performance.now()); };
  }, [expanded, reduceMotion]);
  useLayoutEffect(() => {
    const onKey = (event) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onClose(); }
      if (["Tab", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) {
        event.preventDefault();
        const controls = [...menu.current.querySelectorAll("[role=menuitem]:not([disabled])")];
        const current = controls.indexOf(document.activeElement);
        const next = event.key === "Home" ? 0 : event.key === "End" ? controls.length - 1
          : (current + (event.shiftKey || event.key === "ArrowUp" ? -1 : 1) + controls.length) % controls.length;
        controls[next]?.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return html`
    <div class="menu-popup-layer" onPointerDown=${(event) => {
      if (!menu.current.contains(event.target)) { event.preventDefault(); onClose(); }
      event.stopPropagation();
    }} onWheel=${(event) => { if (!menu.current.contains(event.target)) event.preventDefault(); }}
      onContextMenu=${(event) => event.preventDefault()}>
    <div class="dropdown" role="menu" ref=${menu}>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_SEARCH)}<span>${t.conversationSearch}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_PSYCHOLOGY)}<span>${t.systemPrompt}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_CALL_SPLIT)}<span>${t.forkConversation}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_SHARE)}<span>${t.share}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" onClick=${onSignOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>
    </div>
    </div>`;
}

/**
 * ChatTopBar: title capsule (menu + brand, or the conversation title at conversationTitleSolo)
 * and actions capsule (new chat + more).
 */
function TopBar({ title, conversationId, drawerOpen, onToggleDrawer, menuButton, reduceMotion, onSignedOut, connected }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [retainedMenu, setRetainedMenu] = useState(false);
  const moreButton = useRef(null);
  const titleIdentity = title ? JSON.stringify([conversationId, title]) : "brand";
  const [titleFrames, setTitleFrames] = useState([{ identity: titleIdentity, title, initialAlpha: 1 }]);
  const presentations = titleFrames.some((frame) => frame.identity === titleIdentity) ? titleFrames
    : [...titleFrames, { identity: titleIdentity, title, initialAlpha: 0 }];
  const titleCanvas = useRef(null);
  const titleClip = useRef({ identity: null, target: 0, deadline: 0, animation: null });
  const titleFade = useRef({ identity: titleIdentity, animations: new Map() });
  useLayoutEffect(() => {
    const canvas = titleCanvas.current;
    const clip = titleClip.current;
    const measure = () => {
      const label = [...canvas.querySelectorAll("h1")].find((node) => node.dataset.titleIdentity === titleIdentity);
      const target = Math.min(canvas.clientWidth, 74 + Math.min(label.scrollWidth, 180));
      const initial = clip.identity === null;
      const changed = clip.identity !== titleIdentity;
      if (!changed && clip.target === target && (!reduceMotion || !clip.animation)) return;
      const now = performance.now();
      const from = initial ? target : parseFloat(getComputedStyle(canvas).getPropertyValue("--title-clip-width"));
      canvas.style.setProperty("--title-clip-width", `${from}px`);
      clip.animation?.cancel();
      clip.animation = null;
      if (changed) clip.deadline = now + 400;
      clip.identity = titleIdentity;
      clip.target = target;
      if (initial || reduceMotion || now >= clip.deadline) {
        canvas.style.setProperty("--title-clip-width", `${target}px`);
        clip.deadline = 0;
        return;
      }
      // Identity changes restart; geometry alone rebases within the same original deadline.
      const animation = canvas.animate([
        { "--title-clip-width": `${from}px` }, { "--title-clip-width": `${target}px` },
      ], { duration: clip.deadline - now, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      clip.animation = animation;
      animation.onfinish = () => {
        if (clip.animation !== animation) return;
        canvas.style.setProperty("--title-clip-width", `${clip.target}px`);
        animation.cancel();
        clip.animation = null;
        clip.deadline = 0;
      };
    };
    measure();
    const geometry = new ResizeObserver(measure);
    [canvas, ...canvas.querySelectorAll("h1")].forEach((node) => geometry.observe(node, { box: "border-box" }));
    document.fonts.addEventListener("loadingdone", measure);
    return () => { geometry.disconnect(); document.fonts.removeEventListener("loadingdone", measure); };
  }, [titleIdentity, reduceMotion, titleFrames]);
  useLayoutEffect(() => {
    const fade = titleFade.current;
    if (fade.identity === titleIdentity) return;
    const labels = [...titleCanvas.current.querySelectorAll("h1")];
    for (const label of labels) {
      const alpha = getComputedStyle(label).opacity;
      fade.animations.get(label.dataset.titleIdentity)?.cancel();
      label.style.opacity = alpha;
    }
    fade.animations.clear();
    fade.identity = titleIdentity;
    setTitleFrames(presentations);
    for (const label of labels) {
      const identity = label.dataset.titleIdentity;
      const animation = label.animate([{ opacity: getComputedStyle(label).opacity },
        { opacity: identity === titleIdentity ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" });
      fade.animations.set(identity, animation);
      if (identity === titleIdentity) animation.onfinish = () => {
        if (fade.animations.get(identity) !== animation) return;
        labels.forEach((node) => { node.style.opacity = node === label ? "1" : "0"; });
        fade.animations.forEach((owned) => owned.cancel());
        fade.animations.clear();
        setTitleFrames([{ identity, title, initialAlpha: 1 }]);
      };
    }
  }, [titleIdentity]);
  useLayoutEffect(() => () => {
    titleClip.current.animation?.cancel();
    titleFade.current.animations.forEach((animation) => animation.cancel());
  }, []);
  async function signOut() {
    setMenuOpen(false);
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  function closeMenu() {
    setMenuOpen(false);
  }
  return html`
    <header class="top-bar">
      <div class="capsule title-capsule">
        <div class="title-canvas" ref=${titleCanvas}>
          <div class="title-content">
            <button class="bar-button" type="button" ref=${menuButton} aria-label=${t.menu}
              aria-controls="drawer" aria-expanded=${drawerOpen ? "true" : "false"} onClick=${onToggleDrawer}>
              ${icon(ICON_MENU)}
            </button>
            <div class="title-labels">
              ${presentations.map((frame) => html`<h1 key=${frame.identity}
                class=${frame.title ? "conversation-bar-title" : "brand-title"}
                data-title-identity=${frame.identity} aria-hidden=${frame.identity === titleIdentity ? null : "true"}
                style=${{ opacity: frame.initialAlpha }}>${frame.title || t.title}</h1>`)}
            </div>
          </div>
        </div>
      </div>
      <div class="capsule actions-capsule">
        <button class="bar-button add" type="button" aria-label=${t.newChat} disabled=${!connected}
          onClick=${() => sync.open(null)}>
          ${icon(ICON_ADD)}
        </button>
        <div class="menu-anchor">
          <button class="bar-button" type="button" ref=${moreButton} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen ? "true" : "false"}
            onClick=${() => { setRetainedMenu(true); setMenuOpen(!menuOpen); }}>
            ${icon(ICON_MORE_VERT)}
          </button>
        </div>
      </div>
    </header>
    ${(menuOpen || retainedMenu) && html`<${MoreMenu} expanded=${menuOpen} reduceMotion=${reduceMotion}
      anchor=${moreButton} onSignOut=${signOut} onClose=${closeMenu}
      onExited=${(ownedFocus) => { setRetainedMenu(false); if (ownedFocus) moreButton.current?.focus(); }} />`}`;
}

/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
function Composer({ state }) {
  const field = useRef(null);
  const phase = state.composer?.phase ?? "IDLE";
  const waiting = phase === "WAITING";
  const busy = state.pendingAction || phase !== "IDLE" || state.composer?.stopping;
  const stop = state.generating && !state.text.trim();
  const actionable = state.connected && state.composer && !state.pendingAction && !state.composer.stopping &&
    !["loading", "failed", "deleted"].includes(state.openStatus) &&
    (waiting || (phase === "IDLE" && (stop || (state.text.trim() && state.composer.modelValid))));
  useLayoutEffect(() => {
    const node = field.current;
    const resize = () => {
      node.style.height = "auto";
      node.style.height = Math.min(node.scrollHeight, 6 * 23 + 24) + "px";
    };
    const geometry = new ResizeObserver(resize);
    geometry.observe(node);
    resize();
    return () => geometry.disconnect();
  }, [state.text]);
  useEffect(() => {
    if (!state.snackbar) return;
    const id = state.snackbar.id;
    const timer = setTimeout(() => sync.dismissSnackbar(id), 4_000);
    return () => clearTimeout(timer);
  }, [state.snackbar?.id]);
  return html`
    <div class="composer-host">
      <form class="composer" onSubmit=${(event) => {
        event.preventDefault();
        if (actionable) { if (stop && !busy) sync.stopGeneration(); else sync.submit(); }
      }}>
        <div class="composer-field">
          <textarea ref=${field} rows="1" placeholder=${t.askAgora} aria-label=${t.askAgora}
            value=${state.text} onInput=${(event) => sync.edit(event.currentTarget.value)}></textarea>
          <button class="expand-button" type="button" aria-label=${t.expand} disabled>
            ${icon(ICON_EXPAND_ALL, "0 0 960 960")}
          </button>
        </div>
        <div class="composer-controls">
          <div class="control-group">
            <button class="control-icon" type="button" aria-label=${t.addAttachment} disabled>
              ${icon(ICON_ADD)}
            </button>
            <button class="model-selector" type="button" disabled>${t.selectModel}</button>
            <button class="control-icon" type="button" aria-label=${t.tools} disabled>
              ${icon(ICON_MORE_VERT)}
            </button>
          </div>
          <button class="send-button" type="submit" aria-label=${waiting ? t.cancel : stop ? t.stop : t.send}
            onPointerDown=${(event) => event.preventDefault()}
            disabled=${!actionable} aria-busy=${busy ? "true" : null}>
            ${busy ? html`<span class="spinner" aria-hidden="true"></span>` : icon(stop ? ICON_STOP : ICON_ARROW_UPWARD)}
          </button>
        </div>
      </form>
    </div>`;
}

/**
 * The drawer overlays the chat with a scrim up to 960 px; wider, it sits beside the chat and the
 * chat narrows, as the app's side-by-side drawer. Both start closed and open from the menu button.
 */
export function Shell({ onSignedOut }) {
  const state = useSync();
  useEffect(() => {
    sync.start(onSignedOut);
    return () => sync.stop();
  }, []);
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const shell = useRef(null);
  const drawerTarget = useRef(0);
  const drawerAnimation = useRef(null);
  const drawerDrag = useRef(null);
  const dragClick = useRef(null);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const wasModalOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;
  const reduceMotion = !!state.display?.reduceMotion;
  useLayoutEffect(() => {
    const chat = shell.current.querySelector(".chat");
    const composer = chat.querySelector(".composer-host");
    const capsules = [...chat.querySelectorAll(".top-bar > .capsule")];
    const measure = () => {
      const bounds = chat.getBoundingClientRect();
      const top = Math.max(...capsules.map((node) => node.getBoundingClientRect().bottom)) - bounds.top + 8;
      const bottom = bounds.bottom - composer.getBoundingClientRect().top;
      chat.style.setProperty("--chat-top-inset", `${top}px`);
      chat.style.setProperty("--chat-bottom-inset", `${bottom}px`);
    };
    const geometry = new ResizeObserver(measure);
    [chat, composer, ...capsules].forEach((node) => geometry.observe(node, { box: "border-box" }));
    measure();
    return () => geometry.disconnect();
  }, []);
  // One interpolated progress drives drawer, scrim and desktop inset; freeze its actual value on takeover.
  function freezeDrawer() {
    const progress = Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress"));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    drawerAnimation.current?.cancel();
    drawerAnimation.current = null;
    return progress;
  }
  function settleDrawer(open) {
    const from = freezeDrawer();
    const to = open ? 1 : 0;
    drawerTarget.current = to;
    setDrawerOpen(from > 0 || to > 0);
    if (reduceMotion || from === to) {
      shell.current.style.setProperty("--drawer-progress", String(to));
      setDrawerOpen(to > 0);
      return;
    }
    const animation = shell.current.animate(
      [{ "--drawer-progress": String(from) }, { "--drawer-progress": String(to) }],
      { duration: 300, easing: "cubic-bezier(0, 0, 0.2, 1)", fill: "forwards" },
    );
    drawerAnimation.current = animation;
    animation.onfinish = () => {
      if (drawerAnimation.current !== animation) return;
      shell.current.style.setProperty("--drawer-progress", String(to));
      animation.cancel();
      drawerAnimation.current = null;
      setDrawerOpen(to > 0);
    };
  }
  function endDrawerDrag(event, cancelled = false) {
    const drag = drawerDrag.current;
    if (!drag || (event && drag.id !== event.pointerId)) return;
    drawerDrag.current = null;
    if (shell.current.hasPointerCapture(drag.id)) shell.current.releasePointerCapture(drag.id);
    if (!drag.accepted) {
      if (drag.interrupted) settleDrawer(drawerTarget.current > 0);
      return;
    }
    dragClick.current = drag.id;
    const progress = freezeDrawer();
    const velocity = event && event.timeStamp - drag.time < 100 ? drag.velocity : 0;
    settleDrawer(cancelled ? drawerTarget.current > 0 : velocity === 0 ? progress >= 0.5 : velocity > 0);
  }
  function beginDrawerDrag(event) {
    dragClick.current = null;
    if (sideBySide || !event.isPrimary || event.button !== 0 ||
        event.target.closest(".detail-sheet-layer, .dropdown, dialog, input, textarea, a, [contenteditable]") ||
        !window.getSelection()?.isCollapsed ||
        (event.pointerType === "mouse" && event.target.closest(".markdown, .user-text"))) return;
    for (let node = event.target; node && node !== shell.current; node = node.parentElement) {
      if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return;
    }
    const interrupted = drawerAnimation.current != null;
    drawerDrag.current = { id: event.pointerId, x: event.clientX, y: event.clientY,
      lastX: event.clientX, time: event.timeStamp, velocity: 0, accepted: false, interrupted, progress: freezeDrawer() };
  }
  function moveDrawerDrag(event) {
    const drag = drawerDrag.current;
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (!drag.accepted) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 8) return;
      if (Math.abs(dy) >= Math.abs(dx)) { endDrawerDrag(event, true); return; }
      drag.accepted = true;
      shell.current.setPointerCapture(event.pointerId);
    }
    const elapsed = event.timeStamp - drag.time;
    if (elapsed > 0) drag.velocity = (event.clientX - drag.lastX) / elapsed;
    drag.lastX = event.clientX;
    drag.time = event.timeStamp;
    const progress = Math.max(0, Math.min(1, drag.progress + dx / drawer.current.clientWidth));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    setDrawerOpen(progress > 0);
    event.preventDefault();
  }
  useLayoutEffect(() => {
    const node = shell.current;
    const stopOwnedTouch = (event) => { if (drawerDrag.current?.accepted) event.preventDefault(); };
    const onResize = () => endDrawerDrag(null, true);
    node.addEventListener("touchmove", stopOwnedTouch, { passive: false });
    window.addEventListener("resize", onResize);
    return () => {
      node.removeEventListener("touchmove", stopOwnedTouch);
      window.removeEventListener("resize", onResize);
    };
  }, [sideBySide, reduceMotion]);
  useEffect(() => () => drawerAnimation.current?.cancel(), []);
  useLayoutEffect(() => {
    endDrawerDrag(null, true);
    settleDrawer(drawerTarget.current > 0);
  }, [sideBySide, reduceMotion]);
  // ChatTopBar falls back to the brand while the title is blank.
  const openTitle = state.conversations.find((c) => c.id === state.openId)?.title?.trim() || null;

  useLayoutEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    const enteringModal = modalOpen && !wasModalOpen.current;
    wasModalOpen.current = modalOpen;
    if (!modalOpen) return undefined;
    // The dialog itself takes focus while none of its controls is enabled yet.
    if (enteringModal) {
      (drawer.current?.querySelector("input:not([disabled]), button:not([disabled])") ?? drawer.current)
        ?.focus();
    }
    const onKey = (event) => {
      if (event.defaultPrevented || shell.current.querySelector(".detail-sheet-layer, .dropdown")) return;
      if (event.key === "Escape") { event.preventDefault(); settleDrawer(false); }
      if (event.key === "Tab") {
        const controls = [...drawer.current.querySelectorAll("input:not([disabled]), button:not([disabled])")];
        const first = controls[0] ?? drawer.current;
        const last = controls.at(-1) ?? drawer.current;
        if (!drawer.current.contains(document.activeElement) ||
            (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
          event.preventDefault();
          (event.shiftKey ? last : first).focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen, reduceMotion]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass} ref=${shell} data-blur-effects=${state.display?.blurEffectsEnabled == null ? null : String(state.display.blurEffectsEnabled)}
      data-reduce-motion=${state.display?.reduceMotion == null ? null : String(state.display.reduceMotion)}
      onPointerDown=${beginDrawerDrag} onPointerMove=${moveDrawerDrag}
      onPointerUp=${(event) => endDrawerDrag(event)} onPointerCancel=${(event) => endDrawerDrag(event, true)}
      onLostPointerCapture=${(event) => { if (event.target === shell.current) endDrawerDrag(event, true); }}
      onClickCapture=${(event) => {
        if (event.pointerId === dragClick.current) { event.preventDefault(); event.stopPropagation(); dragClick.current = null; }
      }}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} conversations=${state.conversations} openId=${state.openId}
          connected=${state.connected}
          onSelect=${(id) => { sync.open(id); if (!sideBySide) settleDrawer(false); }} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => settleDrawer(false)}></div>
      <main class="chat" inert=${modalOpen}>
        <${TopBar} title=${openTitle} conversationId=${state.openId} drawerOpen=${drawerOpen} menuButton=${menuButton} reduceMotion=${reduceMotion} onSignedOut=${onSignedOut} connected=${state.connected}
          onToggleDrawer=${() => settleDrawer(drawerTarget.current === 0)} />
        <${MessageList} state=${state} label=${openTitle || t.newChat} />
        <${Composer} state=${state} />
        ${state.snackbar && html`<div class="chat-snackbar" role="status">${state.snackbar.message}</div>`}
      </main>
    </div>`;
}
