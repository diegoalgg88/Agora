import { useEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_DEVICES, ICON_EXPAND_ALL, ICON_LOGOUT,
  ICON_MENU, ICON_MORE_VERT, ICON_PSYCHOLOGY, ICON_REPEAT, ICON_SEARCH, ICON_SETTINGS,
} from "./icons.js";
import { postJson } from "./api.js";
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

function DrawerButton({ className, iconPath, label }) {
  return html`
    <button class=${`drawer-button ${className}`} type="button" disabled>
      ${icon(iconPath)}<span>${label}</span>
    </button>`;
}

/** ChatDrawerContent: title, search, Tasks / Remote, New Chat, the list, Settings. */
function DrawerContent() {
  return html`
    <h2 class="drawer-title">${t.conversations}</h2>
    <div class="drawer-search">
      ${icon(ICON_SEARCH)}
      <input type="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled />
    </div>
    <${DrawerButton} className="tonal group-top" iconPath=${ICON_REPEAT} label=${t.tasks} />
    <${DrawerButton} className="tonal group-bottom" iconPath=${ICON_DEVICES} label=${t.remote} />
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat} />
    <div class="drawer-list" role="list" aria-label=${t.conversations}></div>
    <${DrawerButton} className="tonal settings" iconPath=${ICON_SETTINGS} label=${t.settings} />`;
}

/** AgoraDropdownMenu: 24 dp corners, 48 dp items with an inset capsule highlight. */
function MoreMenu({ onSignOut, onClose }) {
  const menu = useRef(null);
  useEffect(() => {
    menu.current?.querySelector("[role=menuitem]:not([disabled])")?.focus();
    const onPointer = (event) => {
      if (!menu.current?.parentElement?.contains(event.target)) onClose(false);
    };
    const onKey = (event) => {
      if (event.key === "Escape") onClose(true);
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, []);
  return html`
    <div class="dropdown" role="menu" ref=${menu}>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_SEARCH)}<span>${t.conversationSearch}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_PSYCHOLOGY)}<span>${t.systemPrompt}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" onClick=${onSignOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>
    </div>`;
}

/** ChatTopBar in new-chat mode: title capsule (menu + brand) and actions capsule (new chat + more). */
function TopBar({ drawerOpen, onToggleDrawer, menuButton, onSignedOut }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const moreButton = useRef(null);
  async function signOut() {
    setMenuOpen(false);
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  function closeMenu(restoreFocus) {
    setMenuOpen(false);
    if (restoreFocus) moreButton.current?.focus();
  }
  return html`
    <header class="top-bar">
      <div class="capsule title-capsule">
        <button class="bar-button" type="button" ref=${menuButton} aria-label=${t.menu}
          aria-controls="drawer" aria-expanded=${drawerOpen ? "true" : "false"} onClick=${onToggleDrawer}>
          ${icon(ICON_MENU)}
        </button>
        <h1 class="brand-title">${t.title}</h1>
      </div>
      <div class="capsule actions-capsule">
        <button class="bar-button add" type="button" aria-label=${t.newChat} disabled>
          ${icon(ICON_ADD)}
        </button>
        <div class="menu-anchor">
          <button class="bar-button" type="button" ref=${moreButton} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen ? "true" : "false"}
            onClick=${() => setMenuOpen(!menuOpen)}>
            ${icon(ICON_MORE_VERT)}
          </button>
          ${menuOpen && html`<${MoreMenu} onSignOut=${signOut} onClose=${closeMenu} />`}
        </div>
      </div>
    </header>`;
}

/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
function Composer() {
  return html`
    <div class="composer-host">
      <form class="composer" onSubmit=${(event) => event.preventDefault()}>
        <div class="composer-field">
          <textarea rows="1" placeholder=${t.askAgora} aria-label=${t.askAgora} disabled></textarea>
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
          <button class="send-button" type="submit" aria-label=${t.send} disabled>
            ${icon(ICON_ARROW_UPWARD)}
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
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;

  useEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    if (!modalOpen) return undefined;
    // The dialog itself takes focus while none of its controls is enabled yet.
    (drawer.current?.querySelector("input:not([disabled]), button:not([disabled])") ?? drawer.current)
      ?.focus();
    const onKey = (event) => {
      if (event.key === "Escape") setDrawerOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => setDrawerOpen(false)}></div>
      <main class="chat" inert=${modalOpen}>
        <${TopBar} drawerOpen=${drawerOpen} menuButton=${menuButton} onSignedOut=${onSignedOut}
          onToggleDrawer=${() => setDrawerOpen(!drawerOpen)} />
        <section class="messages" aria-label=${t.newChat}></section>
        <${Composer} />
      </main>
    </div>`;
}
