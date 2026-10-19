// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// Only rows near the screen are watched, so the phone sends just those bodies.
import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { Markdown } from "./markdown.js";
import { icon, ICON_BUILD, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT, ICON_IMAGE, ICON_NEUROLOGY } from "./icons.js";
import { sync } from "./sync.js";

// Rows within one screen above or below stay watched, so scrolling rarely meets a blank row.
const WATCH_MARGIN = "100% 0px";

const SHEET_BACK_PATH = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
const SHEET_CLOSE_PATH = "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.41 4.29 19.71 2.88 18.3 9.17 12 2.88 5.7 4.29 4.29 10.59 10.59 16.89 4.29z";

function groupForMessage(message, groupKey) {
  const presentation = message?.presentation;
  if (!presentation) return null;
  if (presentation.compact?.key === groupKey) return presentation.compact;
  return presentation.blocks?.find((block) => block.type === "group" && block.group.key === groupKey)?.group ?? null;
}

function sheetItemsForMessage(message, groupKey) {
  if (!message?.presentation) return [];
  if (groupKey != null) return groupForMessage(message, groupKey)?.items ?? [];
  return message.presentation.blocks
    ?.filter((block) => block.type === "card")
    .map((block) => block.item) ?? [];
}

function canOpenSheetItem(item) {
  return item?.type === "thought" || item?.type === "transcription" ||
    (item?.type === "tool" && item.toolDetail != null);
}

function ToolDocument({ document }) {
  if (!document) return null;
  return html`<div class="tool-document">
    ${document.text != null ? html`<div class="tool-code tool-plain">${document.text}</div>`
      : document.roots.map((node, index) => html`<${ToolJsonNode} key=${index} node=${node} />`)}
    ${document.marker && html`<div class="tool-muted tool-marker">${document.marker}</div>`}
  </div>`;
}

/** Draw only the shared parser's real prefix nodes; never complete or parse JSON in the browser. */
function ToolJsonNode({ node, depth = 0 }) {
  if (node.type === "scalar") return html`<span class=${`tool-scalar ${node.kind === "STRING" ? "" : "tool-code literal"}`}>
    ${node.kind === "NULL" && node.complete ? "\u2014" : node.content}</span>`;
  if (node.type === "array") return html`<div class="tool-json-array">
    ${node.values.map((value, index) => html`<div class="tool-json-array-row" key=${index}>
      <span class="tool-json-label">${index + 1}</span>
      <div class="tool-json-value"><${ToolJsonNode} node=${value} depth=${depth} /></div>
    </div>`)}
  </div>`;
  return html`<div class="tool-json-object">
    ${node.entries.map((entry, index) => {
      const value = entry.value;
      const block = value?.type === "scalar" && value.kind === "STRING" &&
        (value.content.length > 40 || value.content.includes("\n"));
      const nested = value && value.type !== "scalar";
      return html`<div class="tool-json-entry" key=${index}>
        <div class="tool-json-row">
          ${(entry.key || entry.keyComplete) && html`<span class="tool-json-label key">${entry.key}</span>`}
          ${value && !block && (nested
            ? html`<span class="tool-muted">${value.type === "object" ? "{\u2026}" : "[\u2026]"}</span>`
            : html`<div class="tool-json-value"><${ToolJsonNode} node=${value} /></div>`)}
        </div>
        ${block && html`<div class="tool-json-block"><${ToolJsonNode} node=${value} /></div>`}
        ${nested && html`<div class="tool-json-nested" style=${{ paddingLeft: `${(depth + 1) * 16}px` }}>
          <${ToolJsonNode} node=${value} depth=${depth + 1} />
        </div>`}
      </div>`;
    })}
  </div>`;
}

function ToolPill({ text, emphasized = false }) {
  return text == null ? null : html`<span class=${`tool-pill ${emphasized ? "emphasized" : ""}`} title=${text}>${text}</span>`;
}

function ToolOutput({ text }) {
  return html`<div class="tool-output tool-code">${text}</div>`;
}

function ToolBody({ body }) {
  switch (body.type) {
    case "active":
    case "failed":
      return html`<div class=${body.type === "active" ? "tool-active" : "tool-terminal"}>${body.text}</div>
        ${body.output && html`<div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`}`;
    case "stopped": return html`<div class="tool-terminal">${body.text}</div>`;
    case "muted": return html`<div class="tool-muted">${body.text}</div>`;
    case "documents": return body.values.map((document, index) => html`
      <div class="tool-result-document" key=${index}><${ToolDocument} document=${document} /></div>`);
    case "shell": return html`<div class="tool-meta-row">
        <${ToolPill} text=${body.status} emphasized /><${ToolPill} text=${body.device} />
      </div>
      ${body.error && html`<div class="tool-terminal tool-gap">${body.error}</div>`}
      <div class="tool-gap"><${ToolOutput} text=${body.output} /></div>`;
    case "paths": return html`<div class="tool-paths">
      ${body.values.map((path, index) => html`<div class="tool-indexed-line" key=${index}>
        <span class="tool-index">${index + 1}</span><span class="tool-code">${path}</span>
      </div>`)}
    </div>`;
    case "grep": return html`<div class="tool-grep">
      ${body.groups.map((group, index) => html`<div key=${index}>
        <div class="tool-grep-path tool-code">${group.path}</div>
        <div class="tool-matches">${group.matches.map((match, i) => html`<div class="tool-match" key=${i}>
          <span class="tool-line-number">${match.line ?? "\u2014"}</span><span class="tool-code">${match.content}</span>
        </div>`)}</div>
      </div>`)}
    </div>`;
    case "file": return html`
      ${(body.path || body.lineCount) && html`<div class="tool-meta-row tool-file-meta">
        <${ToolPill} text=${body.path} /><${ToolPill} text=${body.lineCount} />
      </div>`}
      ${body.content ? html`<${ToolOutput} text=${body.content} />` : html`<div class="tool-muted">${body.emptyText}</div>`}
      ${body.truncationText && html`<div class="tool-muted tool-gap">${body.truncationText}</div>`}`;
    case "search": return html`<div class="tool-search-results">
      ${body.results.map((result, index) => {
        const Tag = result.safeUrl ? "a" : "div";
        return html`<${Tag} class="tool-search-result" key=${index} href=${result.safeUrl ?? null}
          target=${result.safeUrl ? "_blank" : null} rel=${result.safeUrl ? "noopener noreferrer" : null}>
          <div class="tool-search-title">${result.title}</div>
          ${result.snippet && html`<div class="tool-search-snippet">${result.snippet}</div>`}
          ${result.url && html`<div class="tool-search-url">${result.url}</div>`}
        </${Tag}>`;
      })}
    </div>`;
    default: return null;
  }
}

function toolImageUrl(conversationId, messageId, detailIndex, image) {
  return `/api/tool-images/${encodeURIComponent(conversationId)}/${encodeURIComponent(messageId)}/${detailIndex}/${image.index}?v=${encodeURIComponent(image.version)}`;
}

function ToolImage({ src, image, detail, full = false, onClick }) {
  const [state, setState] = useState("loading");
  const aspect = image.width > 0 && image.height > 0 ? Math.max(0.55, Math.min(2.2, image.width / image.height)) : 1;
  const Tag = full ? "div" : "button";
  return html`<${Tag} class=${`tool-image ${detail.squareCrop && !full ? "square" : ""} ${full ? "full" : ""}`}
    type=${full ? null : "button"} disabled=${full ? null : state !== "loaded"} onClick=${onClick}
    aria-label=${full ? null : detail.imageLabel} data-state=${state} style=${{ aspectRatio: String(aspect) }}>
    <img src=${src} alt=${detail.imageLabel} loading=${full ? "eager" : "lazy"} decoding="async"
      onLoad=${() => setState("loaded")} onError=${() => setState("failed")} />
    <span class="tool-image-overlay loading" aria-hidden=${state !== "loading"}>
      <span class="spinner" role="status" aria-label=${detail.imageLabel}></span>
    </span>
    <span class="tool-image-overlay failed" aria-hidden=${state !== "failed"}>
      <span class="tool-image-failed" role="img" aria-label=${detail.imageFailedLabel}>${icon(ICON_IMAGE)}</span>
    </span>
  </${Tag}>`;
}

function ToolMediaPreview({ detail, urls, initialIndex, onClose }) {
  const dialog = useRef(null);
  const [index, setIndex] = useState(initialIndex);
  const [scale, setScale] = useState(1);
  useEffect(() => {
    dialog.current.showModal();
    return () => dialog.current?.close();
  }, []);
  function navigate(next) { setIndex(next); setScale(1); }
  return html`<dialog class="tool-media-viewer" ref=${dialog} aria-label=${detail.imageLabel}
    onCancel=${(event) => { event.preventDefault(); onClose(); }}
    onKeyDown=${(event) => {
      event.stopPropagation();
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        if (event.key === "ArrowLeft" && index > 0) navigate(index - 1);
        if (event.key === "ArrowRight" && index < urls.length - 1) navigate(index + 1);
      }
    }}>
    <div class="tool-media-scroll" onDblClick=${() => setScale((value) => value === 1 ? 3 : 1)}>
      <div class="tool-media-frame" style=${{ width: `${scale * 100}%`, height: `${scale * 100}%` }}>
        <${ToolImage} key=${urls[index]} src=${urls[index]} image=${detail.images[index]} detail=${detail} full />
      </div>
    </div>
    <div class="tool-media-controls">
      ${urls.length > 1 && html`<span class="tool-media-count">${index + 1} / ${urls.length}</span>`}
      <button class="detail-sheet-icon" type="button" aria-label="Close" onClick=${onClose}>${icon(SHEET_CLOSE_PATH)}</button>
    </div>
    ${urls.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button"
      aria-label="Previous image" disabled=${index === 0} onClick=${() => navigate(index - 1)}>${icon(SHEET_BACK_PATH)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" aria-label="Next image"
        disabled=${index === urls.length - 1} onClick=${() => navigate(index + 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}

function ToolDetail({ item, conversationId, messageId }) {
  const detail = item.toolDetail;
  const [preview, setPreview] = useState(null);
  const urls = detail.images.map((image) => toolImageUrl(conversationId, messageId, item.detailIndex, image));
  const previewVersion = urls.join("\n");
  useEffect(() => { setPreview(null); }, [previewVersion]);
  return html`<div class=${`tool-detail ${detail.kind === "WEB_SEARCH" ? "search" : ""}`}>
    ${detail.arguments && html`<div class="tool-arguments">
      <div class="tool-section-label">${detail.argumentsLabel}</div><${ToolDocument} document=${detail.arguments} />
    </div>`}
    ${detail.kind === "MCP" && html`<div class="tool-mcp-meta tool-meta-row">
      <${ToolPill} text="MCP" emphasized /><${ToolPill} text=${detail.mcpDevice} />
    </div>`}
    <div class="tool-section-label result">${detail.resultLabel}</div>
    ${detail.images.length > 0 && html`<div class="tool-images">
      ${detail.images.map((image, index) => html`<${ToolImage} key=${urls[index]} src=${urls[index]}
        image=${image} detail=${detail} onClick=${() => setPreview(index)} />`)}
    </div>`}
    <${ToolBody} body=${detail.body} />
    ${preview != null && html`<${ToolMediaPreview} detail=${detail} urls=${urls} initialIndex=${preview} onClose=${() => setPreview(null)} />`}
  </div>`;
}

/** UserMessageBubble: plain text in a primaryContainer bubble, 54-300 dp wide. */
function UserBubble({ message }) {
  return html`
    <div class="user-row">
      <div class="user-bubble"><div class="user-text">${message.text.markdown}</div></div>
    </div>`;
}

function CardIcon({ kind }) {
  if (kind === "LOADING") return html`<span class="card-spinner" aria-hidden="true"></span>`;
  const path = kind === "TOOL" ? ICON_BUILD : kind === "IMAGE" ? ICON_IMAGE : ICON_NEUROLOGY;
  return icon(path, kind === "THINKING" ? "0 0 960 960" : "0 0 24 24");
}

function liveTitle(group, strings, elapsed) {
  if (group.liveBaseMs == null || !strings) return group.title;
  const seconds = Math.floor((group.liveBaseMs + elapsed) / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remaining = seconds % 60;
  const template = hours ? strings.hours : seconds >= 60 ? strings.minutes : strings.seconds;
  const args = hours ? [hours, minutes, remaining] : seconds >= 60 ? [minutes, remaining] : [seconds];
  let index = 0;
  return template.replace(/%(?:(\d+)\$)?d/g, (_, position) => args[position ? Number(position) - 1 : index++] ?? "");
}

function useCardTitle(group, strings) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    setElapsed(0);
    if (group.liveBaseMs == null) return undefined;
    const started = Date.now();
    const timer = setInterval(() => setElapsed(Date.now() - started), 1000);
    return () => clearInterval(timer);
  }, [group.liveBaseMs]);
  return liveTitle(group, strings, elapsed);
}

function createGroupExpansionController() {
  const states = new Map();
  const collapsedImageBoundaryKeys = new Set();
  return {
    reset() {
      states.clear();
      collapsedImageBoundaryKeys.clear();
    },
    shouldCollapseForImageBoundary(key, hasImageBoundary) {
      return hasImageBoundary && !collapsedImageBoundaryKeys.has(key);
    },
    claimImageBoundaryCollapse(key, hasImageBoundary) {
      if (!hasImageBoundary || collapsedImageBoundaryKeys.has(key)) return false;
      collapsedImageBoundaryKeys.add(key);
      states.set(key, "INACTIVE");
      return true;
    },
    shouldPresentInitiallyExpanded(key, isActive, enabled) {
      return enabled && isActive && !states.has(key);
    },
    update(key, isActive, enabled) {
      if (collapsedImageBoundaryKeys.has(key)) return null;
      if (!enabled) {
        if (isActive) states.delete(key);
        else states.set(key, "INACTIVE");
        return null;
      }
      switch (states.get(key)) {
        case undefined:
        case "INACTIVE":
          states.set(key, isActive ? "ACTIVE" : "INACTIVE");
          return isActive ? "EXPAND" : null;
        case "ACTIVE":
          if (!isActive) {
            states.set(key, "INACTIVE");
            return "COLLAPSE";
          }
          return null;
        default:
          return null;
      }
    },
  };
}

/** Activate only segments whose detail projection is available. */
function InfoItem({ item, compact = false, onClick }) {
  const text = item.type === "thought" ? item.content?.markdown?.replace(/\n/g, " ")
    : item.type === "transcription" ? item.content?.markdown?.replace(/\n/g, " ") || "Image transcription is empty."
      : item.summary;
  const interactive = typeof onClick === "function";
  const Tag = interactive ? "button" : "div";
  return html`
    <${Tag} class=${`${compact ? "info-item compact-item" : "info-item timeline-item"} ${interactive ? "sheet-item" : ""}`}
      type=${interactive ? "button" : null}
      onClick=${onClick}>
      ${!compact && html`<span class="info-item-icon">
        <${CardIcon} kind=${item.type === "tool" ? "TOOL" : item.type === "transcription" ? "IMAGE" : "THINKING"} />
      </span>`}
      <div class="info-item-text">
        <span class="info-item-title">${item.title}</span>
        ${text && html`<span class="info-item-summary">${text}</span>`}
      </div>
      ${!compact && interactive && html`<span class="info-item-arrow">${icon(ICON_CHEVRON_RIGHT)}</span>`}
    </${Tag}>`;
}

/** Browser-local expansion memory survives payload eviction and off-screen row hydration. */
function InfoGroup({ group, messageId, display, expansion, expansionController, opensSheet, onOpenSheet, onOpenDetail, appearances, streaming }) {
  const key = `${messageId}:${group.key}`;
  const initiallyActive = !!(display?.autoExpandActiveGroup && group.autoExpansionActive);
  const imageBoundary = group.imageDetailIndex != null;
  const autoExpandEnabled = !!display?.autoExpandActiveGroup;
  const initiallyAutoExpanded = expansionController.shouldPresentInitiallyExpanded(
    key,
    initiallyActive,
    autoExpandEnabled,
  );
  const [expanded, setExpanded] = useState(() =>
    expansionController.shouldCollapseForImageBoundary(key, imageBoundary)
      ? false
      : expansion.get(key) ?? initiallyAutoExpanded,
  );
  const surface = useRef(null);
  const titleNode = useRef(null);
  const title = useCardTitle(group, display?.liveThinking);
  const [widths, setWidths] = useState(null);
  const firstAppearance = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  useEffect(() => {
    const parent = surface.current?.parentElement;
    if (!parent || !titleNode.current) return undefined;
    const measure = () => {
      const text = titleNode.current;
      const canvas = document.createElement("canvas");
      const context = canvas.getContext("2d");
      context.font = getComputedStyle(text).font;
      setWidths({
        collapsed: Math.min(parent.clientWidth, Math.ceil(context.measureText(text.textContent).width) + 80),
        expanded: parent.clientWidth,
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(parent);
    return () => observer.disconnect();
  }, [title]);
  useEffect(() => {
    if (expansionController.shouldCollapseForImageBoundary(key, imageBoundary)) {
      if (expansionController.claimImageBoundaryCollapse(key, imageBoundary)) {
        expansion.set(key, false);
        setExpanded(false);
      }
      return;
    }
    const action = expansionController.update(key, initiallyActive, autoExpandEnabled);
    if (action === null) return;
    const nextExpanded = action === "EXPAND";
    expansion.set(key, nextExpanded);
    setExpanded(nextExpanded);
  }, [key, initiallyActive, autoExpandEnabled, imageBoundary, expansionController]);
  function toggle() {
    expansion.set(key, !expanded);
    setExpanded(!expanded);
  }
  const targetExpanded = expanded && !opensSheet;
  return html`
    <div class=${`info-group ${targetExpanded ? "expanded" : ""} ${opensSheet ? "sheet-mode" : ""} ${group.precededByAnswer ? "after-answer" : ""} ${group.items.some((item) => item.type === "tool") ? "has-tool" : ""} ${firstAppearance.current ? "entering" : ""}`}
      data-group=${group.key}
      style=${widths == null ? null : { "--card-content-width": `${targetExpanded ? widths.expanded : widths.collapsed}px`, "--card-expanded-width": `${widths.expanded}px` }}>
      <div class="info-group-surface" ref=${surface}
        style=${widths == null ? null : { width: `${targetExpanded ? widths.expanded : widths.collapsed}px` }}>
        <button class="info-group-header" type="button" aria-expanded=${opensSheet ? null : expanded}
          aria-haspopup=${opensSheet ? "dialog" : null}
          onClick=${opensSheet ? () => onOpenSheet?.(messageId, group.key) : toggle}>
          <span class="info-header-icon"><${CardIcon} kind=${group.icon} /></span>
          <span class="info-header-title" ref=${titleNode}>${title}</span>
          <span class="info-disclosure">${icon(ICON_CHEVRON_DOWN)}</span>
        </button>
        <div class="info-group-reveal" inert=${!targetExpanded}>
          <div class="info-group-items">
            ${group.items.map((item) => html`<${InfoItem} key=${item.detailIndex} item=${item} compact
              onClick=${canOpenSheetItem(item)
                ? () => onOpenDetail(messageId, group.key, item.detailIndex) : undefined} />`)}
          </div>
        </div>
      </div>
    </div>`;
}

function InfoCard({ block, messageId, appearances, streaming, onOpenDetail }) {
  const key = `${messageId}:card:${block.item.detailIndex}`;
  const entering = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  return html`
    <div class=${`info-card ${block.groupPosition.toLowerCase()} ${block.precededByAnswer ? "after-answer" : ""} ${block.item.type === "tool" ? "has-tool" : ""} ${entering.current ? "entering" : ""}`}
      data-detail=${block.item.detailIndex}>
      <div class="info-card-surface"><${InfoItem} item=${block.item}
        onClick=${canOpenSheetItem(block.item)
          ? () => onOpenDetail(messageId, null, block.item.detailIndex) : undefined} /></div>
    </div>`;
}

function DetailSheet({ group, items, page, detailIndex, selectedItem, conversationId, messageId, display, wrap, onSelectItem, onBack, onClose }) {
  const [expanded, setExpanded] = useState(false);
  const closeButton = useRef(null);
  const restoreFocus = useRef(null);
  const sheet = useRef(null);
  const dragStart = useRef(null);
  const suppressHandleClick = useRef(false);
  const backAction = useRef(onBack);
  backAction.current = onBack;
  const groupTitle = useCardTitle(group ?? { title: "", liveBaseMs: null }, display?.liveThinking);
  const title = page === "detail" ? selectedItem?.title : groupTitle;
  useEffect(() => {
    restoreFocus.current = document.activeElement;
    closeButton.current?.focus();
    const onKey = (event) => {
      if (event.target.closest?.(".tool-media-viewer")) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        backAction.current();
      } else if (event.key === "Tab") {
        const controls = [...sheet.current?.querySelectorAll("button:not([disabled]), a[href]") ?? []];
        if (!controls.length) return;
        const target = event.shiftKey ? controls.at(-1) : controls[0];
        const atEdge = event.shiftKey ? document.activeElement === controls[0]
          : document.activeElement === controls.at(-1);
        if (atEdge || !sheet.current?.contains(document.activeElement)) {
          event.preventDefault();
          target.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (restoreFocus.current?.isConnected) restoreFocus.current.focus();
    };
  }, []);
  useEffect(() => {
    sheet.current?.querySelector(".detail-sheet-content")?.scrollTo(0, 0);
  }, [page, detailIndex]);
  function beginDrag(event) {
    dragStart.current = event.clientY;
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }
  function endDrag(event) {
    if (dragStart.current == null) return;
    const delta = event.clientY - dragStart.current;
    dragStart.current = null;
    if (Math.abs(delta) > 24) {
      suppressHandleClick.current = true;
      if (delta < 0) setExpanded(true);
      else setExpanded(false);
    }
  }
  function onWheel(event) {
    const content = sheet.current?.querySelector(".detail-sheet-content");
    if (event.deltaY < 0 && content?.scrollTop === 0 && expanded) setExpanded(false);
    else if (event.deltaY > 0 && !expanded) setExpanded(true);
  }
  return html`
    <div class="detail-sheet-layer">
      <button class="detail-sheet-backdrop" type="button" aria-label="Close" onClick=${onClose}></button>
      <section class=${`detail-sheet ${expanded ? "expanded" : ""}`} role="dialog" aria-modal="true"
        aria-label=${title || "Message details"} ref=${sheet}>
        <button class="detail-sheet-handle" type="button"
          aria-label=${expanded ? "Collapse details" : "Expand details"}
          onPointerDown=${beginDrag} onPointerUp=${endDrag} onPointerCancel=${() => { dragStart.current = null; }}
          onClick=${() => {
            if (suppressHandleClick.current) suppressHandleClick.current = false;
            else setExpanded((value) => !value);
          }}>
          <span></span>
        </button>
        <header class="detail-sheet-header">
          ${page === "detail" && group && html`<button class="detail-sheet-icon detail-sheet-back" type="button" aria-label="Back" onClick=${onBack}>
            ${icon(SHEET_BACK_PATH)}
          </button>`}
          <h2>${title || "Message details"}</h2>
          <button class="detail-sheet-icon" type="button" aria-label="Close" ref=${closeButton} onClick=${onClose}>
            ${icon(SHEET_CLOSE_PATH)}
          </button>
        </header>
        <div class="detail-sheet-content" onWheel=${onWheel}>
          <div class=${`detail-sheet-page ${page === "list" ? "list-page" : "detail-page"}`} key=${page}>
            ${page === "list" ? html`
              <div class="detail-sheet-list">
                ${items.map((item, index) => html`
                  <div class=${`detail-sheet-list-row ${index === 0 ? "first" : index === items.length - 1 ? "last" : "middle"}`} key=${item.detailIndex}>
                    <${InfoItem} item=${item}
                      onClick=${canOpenSheetItem(item) ? () => onSelectItem(item.detailIndex) : undefined} />
                  </div>`)}
              </div>` : selectedItem?.type === "tool" ? html`
                <${ToolDetail} key=${detailIndex} item=${selectedItem} conversationId=${conversationId} messageId=${messageId} />`
              : selectedItem && html`
              <div class=${`detail-sheet-markdown ${selectedItem.streaming ? "streaming" : ""}`}>
                ${selectedItem.type === "transcription" && !selectedItem.content?.markdown
                  ? html`<p class="detail-sheet-empty">Image transcription is empty.</p>`
                  : html`<${Markdown} text=${selectedItem.content} variant="thought" wrap=${wrap} />`}
              </div>`}
          </div>
        </div>
      </section>
    </div>`;
}

/** AssistantMessageContent reads the same presentation decisions as the phone. */
function ModelMessage({ message, wrap, display, expansion, expansionController, appearances, streaming, onOpenSheet, onOpenDetail }) {
  const presentation = message.presentation;
  const error = message.participant === "ERROR";
  let body = null;
  if (presentation?.useTimeline) {
    body = presentation.blocks.map((block) => {
      switch (block.type) {
        case "answer":
          return html`<div class="answer-block" key=${`a${block.index}`}>
            <${Markdown} text=${block.text} wrap=${wrap} />
          </div>`;
        case "group":
          return html`<${InfoGroup} key=${block.group.key} group=${block.group}
            messageId=${message.id} display=${display} expansion=${expansion}
            expansionController=${expansionController} onOpenSheet=${onOpenSheet}
            onOpenDetail=${onOpenDetail}
            appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`;
        case "card":
          return html`<${InfoCard} key=${`card:${block.item.detailIndex}`} block=${block}
            messageId=${message.id} appearances=${appearances} streaming=${streaming}
            onOpenDetail=${onOpenDetail} />`;
        default:
          return null;
      }
    });
  } else {
    body = html`
      ${presentation?.compact && html`<${InfoGroup} group=${presentation.compact} messageId=${message.id}
        display=${display} expansion=${expansion} expansionController=${expansionController}
        onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail}
        appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`}
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} wrap=${wrap} />`}`;
  }
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}</div>`;
}

function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming, onOpenSheet, onOpenDetail }) {
  const message = body ?? null;
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER"
      ? html`<${UserBubble} message=${message} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} display=${display}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming}
          onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail} />`;
  }
  return html`<div class="message-row" data-id=${entry.id}>${content}</div>`;
}

export function MessageList({ state, label }) {
  const scroller = useRef(null);
  const visible = useRef(new Set());
  const pinned = useRef(false);
  const cover = useRef(null);
  const [settledOpenId, setSettledOpenId] = useState(null);
  const [retainedCover, setRetainedCover] = useState(false);
  const switching = !!state.openId && (state.openStatus === "loading" ||
    (state.openStatus === "ready" && settledOpenId !== state.openId));
  const covered = switching || retainedCover;
  const expansion = useRef(new Map());
  const expansionController = useRef(createGroupExpansionController());
  const appearances = useRef(new Set());
  const previousOpenId = useRef(state.openId);
  const [sheet, setSheet] = useState(null);
  const sheetWatchedId = sheet?.conversationId === state.openId ? sheet.messageId : null;
  const watchedSheet = useRef(sheetWatchedId);
  watchedSheet.current = sheetWatchedId;
  const ids = state.path.map((entry) => entry.id).join(",");
  const wrap = state.display?.autoWrapCodeBlocks ?? true;
  const selectedOnPath = sheet && state.openId === sheet.conversationId &&
    state.path.some((entry) => entry.id === sheet.messageId);
  const sheetMessage = selectedOnPath
    ? state.streaming?.id === sheet.messageId ? state.streaming : state.bodies.get(sheet.messageId)
    : null;
  const sheetGroup = sheetMessage && sheet?.groupKey != null
    ? groupForMessage(sheetMessage, sheet.groupKey) : null;
  const sheetItems = sheetMessage ? sheetItemsForMessage(sheetMessage, sheet.groupKey) : [];
  const selectedItem = sheetItems.find((item) => item.detailIndex === sheet?.detailIndex);
  const validSheet = selectedOnPath && (!sheetMessage ||
    ((sheet.groupKey == null || sheetGroup) &&
      (sheet.page !== "detail" || canOpenSheetItem(selectedItem))));

  function openSheet(messageId, groupKey) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "list", detailIndex: null });
  }
  function openDetail(messageId, groupKey, detailIndex) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "detail", detailIndex });
  }
  useEffect(() => {
    if (!sheet) return;
    if (sheet.conversationId !== state.openId || state.openStatus === "deleted" ||
        state.openStatus === "failed" ||
        (state.openStatus === "ready" && !state.path.some((entry) => entry.id === sheet.messageId)) ||
        (sheetMessage && !validSheet)) setSheet(null);
  }, [sheet, state.openId, state.openStatus, ids, sheetMessage, validSheet]);

  useEffect(() => {
    if (previousOpenId.current === state.openId) return;
    previousOpenId.current = state.openId;
    expansion.current.clear();
    expansionController.current.reset();
    appearances.current.clear();
  }, [state.openId]);

  useEffect(() => {
    const root = scroller.current;
    if (!root) return undefined;
    visible.current = new Set();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const id = entry.target.dataset.id;
        if (entry.isIntersecting) visible.current.add(id);
        else visible.current.delete(id);
      }
      const watched = new Set(visible.current);
      if (watchedSheet.current) watched.add(watchedSheet.current);
      sync.watch([...watched]);
    }, { root, rootMargin: WATCH_MARGIN });
    root.querySelectorAll(".message-row").forEach((row) => observer.observe(row));
    return () => observer.disconnect();
  }, [ids]);

  useEffect(() => {
    const watched = new Set(visible.current);
    if (sheetWatchedId) watched.add(sheetWatchedId);
    sync.watch([...watched]);
  }, [sheetWatchedId]);

  // The app opens a conversation at its newest message. Row bodies arrive after the path, so
  // this same owner releases the cover after visible bodies and bottom layout have settled.
  useLayoutEffect(() => {
    pinned.current = true;
    setSettledOpenId(null);
  }, [state.openId]);
  useLayoutEffect(() => {
    const root = scroller.current;
    const column = root?.firstElementChild;
    if (!root || !column) return undefined;
    let frame = 0;
    let previousLayout = null;
    const pending = state.openStatus === "ready" && settledOpenId !== state.openId;
    const request = state.scrollRequest;
    if (request && state.openStatus === "ready" && state.path.some((entry) => entry.id === request.messageId)) {
      pinned.current = true;
      sync.consumeScroll(request);
    }
    const settle = () => {
      frame = 0;
      const viewport = root.getBoundingClientRect();
      const rows = [...column.querySelectorAll(".message-row")].filter((row) => {
        const bounds = row.getBoundingClientRect();
        return bounds.bottom > viewport.top && bounds.top < viewport.bottom;
      });
      if (rows.some((row) => state.streaming?.id !== row.dataset.id && !state.bodies.has(row.dataset.id))) {
        previousLayout = null;
        return;
      }
      const layout = `${root.scrollHeight}:${root.clientHeight}:${root.scrollTop}`;
      if (layout === previousLayout && Math.abs(root.scrollHeight - root.clientHeight - root.scrollTop) <= 1) {
        setSettledOpenId(state.openId);
      } else {
        previousLayout = layout;
        frame = requestAnimationFrame(settle);
      }
    };
    const followBottom = () => {
      if (pinned.current) root.scrollTop = root.scrollHeight;
      if (pending && !frame) frame = requestAnimationFrame(settle);
    };
    const release = () => { pinned.current = false; };
    const follow = new ResizeObserver(followBottom);
    follow.observe(column);
    follow.observe(root);
    followBottom();
    const inputs = ["wheel", "touchstart", "keydown", "pointerdown"];
    inputs.forEach((type) => root.addEventListener(type, release, { passive: true }));
    return () => {
      follow.disconnect();
      cancelAnimationFrame(frame);
      inputs.forEach((type) => root.removeEventListener(type, release));
    };
  }, [state.openId, state.openStatus, ids, state.bodies, state.streaming, settledOpenId, state.scrollRequest]);

  useLayoutEffect(() => {
    const node = cover.current;
    if (!node) return undefined;
    setRetainedCover(true);
    const animation = node.animate(
      [{ opacity: getComputedStyle(node).opacity }, { opacity: switching ? 1 : 0 }],
      { duration: 200, easing: "cubic-bezier(0.4, 0, 0.2, 1)", fill: "forwards" },
    );
    animation.onfinish = () => {
      node.style.opacity = switching ? "1" : "0";
      animation.cancel();
      if (!switching) setRetainedCover(false);
    };
    return () => {
      node.style.opacity = getComputedStyle(node).opacity;
      animation.cancel();
    };
  }, [switching]);

  return html`
    <section class="messages" ref=${scroller} aria-label=${label} aria-busy=${covered} inert=${covered}>
      <div class="message-column">
        ${state.path.map((entry) => html`
          <${Row} key=${entry.id} entry=${entry} wrap=${wrap} display=${state.display}
            expansion=${expansion.current} expansionController=${expansionController.current}
            appearances=${appearances.current}
            onOpenSheet=${openSheet} onOpenDetail=${openDetail}
            streaming=${state.streaming?.id === entry.id}
            body=${state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} />`)}
      </div>
    </section>
    ${covered && html`<div class="conversation-loading-cover" ref=${cover}
      onPointerDown=${(event) => { event.preventDefault(); event.stopPropagation(); }}
      onWheel=${(event) => event.preventDefault()} onContextMenu=${(event) => event.preventDefault()}>
      <div class="conversation-loading-range">
        <span class="spinner" role="progressbar" aria-label=${label}></span>
      </div>
    </div>`}
    ${sheet && validSheet && html`<${DetailSheet} key=${`${sheet.conversationId}:${sheet.messageId}:${sheet.groupKey ?? "direct"}`}
      group=${sheetGroup} items=${sheetItems} page=${sheet.page} detailIndex=${sheet.detailIndex}
      selectedItem=${selectedItem} display=${state.display} wrap=${wrap}
      conversationId=${sheet.conversationId} messageId=${sheet.messageId}
      onSelectItem=${(detailIndex) => setSheet({ ...sheet, page: "detail", detailIndex })}
      onBack=${() => sheet.page === "detail" && sheetGroup
        ? setSheet({ ...sheet, page: "list", detailIndex: null }) : setSheet(null)}
      onClose=${() => setSheet(null)} />`}`;
}
