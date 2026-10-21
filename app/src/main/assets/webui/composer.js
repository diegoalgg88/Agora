import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_EXPAND_ALL, ICON_MORE_VERT, ICON_STOP, ICON_CHECK, ICON_CLOSE, ICON_ATTACH_FILE } from "./icons.js";
import { sync } from "./sync.js";
import { Markdown } from "./markdown.js";
import { ICON_IMAGE, ICON_CHEVRON_RIGHT, ICON_CAMERA, ICON_VIDEO, ICON_ERROR, ICON_BROKEN_IMAGE } from "./icons.js";

/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
export function Composer({ state, MoreMenu }) {
  const field = useRef(null);
  const modelButton = useRef(null);
  const addButton = useRef(null);
  const picker = useRef(null);
  const pickerTarget = useRef(null);
  const [addOpen, setAddOpen] = useState(false);
  const [retainedAdd, setRetainedAdd] = useState(false);
  const [viewer, setViewer] = useState(null);
  const attachments = state.composer?.attachments ?? [];
  const [modelOpen, setModelOpen] = useState(false);
  const [retainedModelMenu, setRetainedModelMenu] = useState(false);
  const modelChoices = Object.entries(state.composer?.models ?? {});
  const selectedModel = state.composer?.modelId;
  const modelLabel = state.composer?.modelValid ? state.composer.models?.[selectedModel] ?? t.selectModel
    : modelChoices.length ? t.selectModel : t.noModel;
  useEffect(() => { setModelOpen(false); }, [state.openId, state.connected]);
  const phase = state.composer?.phase ?? "IDLE";
  const waiting = phase === "WAITING";
  const busy = state.pendingAction || phase !== "IDLE" || state.composer?.stopping;
  const stop = state.generating && !state.text.trim() && !attachments.length;
  const canDrain = !state.generating && !state.text.trim() && !attachments.length && state.composer?.queue?.length;
  const editable = state.connected && !!state.composer && !state.pendingAction && phase === "IDLE";
  useEffect(() => { if (!editable) setAddOpen(false); }, [editable]);
  useEffect(() => { setAddOpen(false); }, [state.openId, state.connectionId]);
  const viewedId = viewer?.file?.id ?? viewer?.items?.[viewer.index]?.id;
  const currentViewer = viewer && viewer.connectionId === state.connectionId && viewer.seq === state.composer?.seq &&
    attachments.some(a => a.id === viewedId && !a.unavailable && a.storage === "APP_PRIVATE");
  useEffect(() => { if (viewer && !currentViewer) setViewer(null); }, [viewer, currentViewer]);
  const pending = state.connected && phase === "IDLE" && (attachments.find(a => a.type === "pdf" && a.state === "PROCESSING" && a.pageCount > 0 && a.selectedPages == null)
    ?? attachments.find(a => a.type === "video" && a.state === "PROCESSING" && a.staged && a.frameCount == null));
  function choose(kind) {
    const node = picker.current;
    pickerTarget.current = { target: sync.attachmentTarget(), forced: kind === "videos" ? "video" : kind === "files" ? null : "image" };
    node.accept = kind === "videos" ? "video/*" : kind === "files" ? "*/*" : "image/*";
    node.multiple = kind !== "camera";
    if (kind === "camera") node.setAttribute("capture", "environment"); else node.removeAttribute("capture");
    node.value = "";
    node.click();
    setAddOpen(false);
  }
  function preview(a) {
    const media = a.type === "pdf" ? Array.from({ length: a.pagePreviewCount }, (_, index) => ({ ...a, kind: "page", index }))
      : attachments.filter(item => ["image", "video"].includes(item.type) && item.state === "READY" && !item.unavailable && item.storage === "APP_PRIVATE")
        .map(item => ({ ...item, kind: "source", index: 0 }));
    setViewer({ ...sync.attachmentTarget(), ...(a.type === "file" ? { file: a } : { items: media, index: Math.max(0, media.findIndex(item => item.id === a.id)) }) });
  }
  const actionable = state.connected && state.composer && !state.pendingAction && !state.composer.stopping &&
    !["loading", "failed", "deleted"].includes(state.openStatus) &&
    (waiting || (phase === "IDLE" && (stop || ((state.text.trim() || attachments.length || canDrain) && state.composer.modelValid))));
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
        ${(state.composer?.queue ?? []).map(queued => html`
          <div class="queued-message" key=${queued.id}>
            <span class="queued-text">${queued.text}</span>
            ${queued.attachmentCount > 0 && html`<span class="queued-attachments" aria-label=${t.attachments}>
              ${icon(ICON_ATTACH_FILE)}${queued.attachmentCount}</span>`}
            <button type="button" aria-label=${t.remove} disabled=${!state.connected}
              onClick=${() => sync.removeQueued(queued.id)}>${icon(ICON_CLOSE)}</button>
          </div>`)}
        ${attachments.length > 0 && html`<div class="attachment-row" aria-label=${t.attachments}>
          ${attachments.map(a => html`<${AttachmentTile} key=${`${state.connectionId}:${state.composer.seq}:${a.id}`} attachment=${a} editable=${editable} onPreview=${() => preview(a)} />`)}
        </div>`}
        <input class="attachment-input" type="file" ref=${picker} onChange=${event => {
          const captured = pickerTarget.current;
          if (captured) sync.uploadFiles([...event.currentTarget.files], captured.forced, captured.target);
          event.currentTarget.value = "";
        }} />
        <div class="composer-field">
          <textarea ref=${field} rows="1" placeholder=${t.askAgora} aria-label=${t.askAgora}
            value=${state.text} onInput=${(event) => sync.edit(event.currentTarget.value)}></textarea>
          <button class="expand-button" type="button" aria-label=${t.expand} disabled>
            ${icon(ICON_EXPAND_ALL, "0 0 960 960")}
          </button>
        </div>
        <div class="composer-controls">
          <div class="control-group">
            <button ref=${addButton} class="control-icon" type="button" aria-label=${t.addAttachment} disabled=${!editable || !state.connectionId}
              aria-haspopup="menu" aria-expanded=${addOpen} onClick=${() => { setRetainedAdd(true); setAddOpen(!addOpen); }}>
              ${icon(ICON_ADD)}
            </button>
            <button ref=${modelButton} class="model-selector" type="button" aria-label=${t.selectModel}
              aria-haspopup="menu" aria-expanded=${modelOpen} data-valid=${!!state.composer?.modelValid}
              disabled=${!state.connected || !state.composer || !!state.pendingAction}
              onClick=${() => { setRetainedModelMenu(true); setModelOpen(!modelOpen); }}>${modelLabel}</button>
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
    </div>
      ${retainedModelMenu && html`<${MoreMenu} expanded=${modelOpen} reduceMotion=${state.display.reduceMotion}
        anchor=${modelButton} above=${true} onClose=${() => setModelOpen(false)}
        onExited=${(restoreFocus) => { setRetainedModelMenu(false); if (restoreFocus) modelButton.current?.focus(); }}>
          ${modelChoices.length ? modelChoices.map(([id, label]) => html`
            <button class="dropdown-item" type="button" role="menuitemradio" aria-checked=${id === selectedModel}
              onClick=${() => { sync.selectModel(id); setModelOpen(false); }}>
              <span class="model-check">${id === selectedModel && icon(ICON_CHECK)}</span><span>${label}</span>
            </button>`) : html`<button class="dropdown-item" type="button" role="menuitem" disabled>${t.noModels}</button>`}
      </${MoreMenu}>`}
      ${retainedAdd && html`<${MoreMenu} expanded=${addOpen} reduceMotion=${state.display.reduceMotion} anchor=${addButton} above
        onClose=${() => setAddOpen(false)} onExited=${restore => { setRetainedAdd(false); if (restore) addButton.current?.focus(); }}>
        ${[["camera", ICON_CAMERA], ["photos", ICON_IMAGE], ["videos", ICON_VIDEO], ["files", ICON_ATTACH_FILE]].map(([kind, path]) => html`
          <button class="dropdown-item" role="menuitem" type="button" disabled=${!editable} onClick=${() => choose(kind)}>${icon(path)}<span>${t[kind]}</span></button>`)}
      </${MoreMenu}>`}
      ${pending && html`<${AttachmentEditor} key=${`${state.connectionId}:${state.composer.seq}:${pending.id}`} attachment=${pending} editable=${editable}
        onPreview=${index => setViewer({ ...sync.attachmentTarget(), items: Array.from({ length: pending.pagePreviewCount }, (_, i) => ({ ...pending, kind: "page", index: i })), index })} />`}
      ${currentViewer && html`<${AttachmentViewer} viewer=${viewer}
        onNavigate=${index => setViewer(current => current === viewer ? { ...current, index } : current)}
        onClose=${() => setViewer(current => current === viewer ? null : current)} />`}`;
}

function AttachmentTile({ attachment: a, editable, onPreview }) {
  const source = a.state === "READY" && !a.unavailable && a.storage === "APP_PRIVATE"
    ? a.type === "image" ? sync.attachmentUrl(a.id, "source") : a.type === "video" && a.framePreviewCount ? sync.attachmentUrl(a.id, "frame") : null : null;
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const decoded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  const [loading, setLoading] = useState(false);
  const [initial, setInitial] = useState(true);
  useLayoutEffect(() => { const frame = requestAnimationFrame(() => setInitial(false)); return () => cancelAnimationFrame(frame); }, []);
  const busy = a.state === "PROCESSING" || (!!source && !decoded && !failed);
  useEffect(() => { setLoading(false); if (!busy) return; const timer = setTimeout(() => setLoading(true), 200); return () => clearTimeout(timer); }, [busy]);
  const blocked = a.unavailable || a.storage !== "APP_PRIVATE";
  const retry = a.state === "FAILED" || failed;
  const canPreview = !blocked && a.state === "READY" && (a.type === "file" ? a.text != null : a.type === "pdf" ? a.pagePreviewCount > 0 : !source || decoded);
  const fileStyle = a.type !== "video" && a.type !== "image";
  const label = a.type === "pdf" ? "PDF" : (a.name?.includes(".") ? a.name.split(".").at(-1) : a.type).toUpperCase().slice(0, 4);
  return html`<div class="attachment-tile" data-state=${a.unavailable ? "unavailable" : a.state.toLowerCase()}>
    <button class="attachment-preview" type="button" title=${a.name || t.attachments} aria-label=${retry ? t.retry : a.name || t.attachments}
      disabled=${retry ? !editable : !canPreview} onClick=${() => retry ? sync.attachmentCommand("attachment_retry", a.id) : onPreview()}>
      ${source && html`<img key=${source} src=${source} alt="" onLoad=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)} style=${{ opacity: decoded ? 1 : 0 }} />`}
      <span class=${`attachment-status ${fileStyle ? a.type === "pdf" ? "pdf-placeholder" : "file-placeholder" : ""}`} style=${{ opacity: initial || decoded ? 0 : 1 }}>
        ${fileStyle ? html`<small>${label}</small>` : icon(a.type === "video" ? ICON_VIDEO : ICON_IMAGE)}
      </span>
      <span class="attachment-status attachment-progress" style=${{ opacity: !initial && busy && loading ? 1 : 0 }}><span class="spinner"></span></span>
      <span class="attachment-status attachment-error" style=${{ opacity: !initial && retry ? 1 : 0 }}>${icon(a.type === "image" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</span>
    </button>
    <button class="attachment-remove" type="button" title=${t.remove} aria-label=${t.remove} disabled=${!editable}
      onClick=${() => sync.attachmentCommand("attachment_remove", a.id)}>${icon(ICON_CLOSE)}</button>
  </div>`;
}

function AttachmentEditor({ attachment: a, editable, onPreview }) {
  const dialog = useRef(null);
  const pdf = a.type === "pdf";
  const [pages, setPages] = useState(() => Array.from({ length: Math.min(5, a.pageCount || 0) }, (_, i) => i));
  const [countMode, setCountMode] = useState(true);
  const [count, setCount] = useState(String(a.defaultFrameCount));
  const seconds = Math.floor((a.durationMs || 0) / 1000);
  const [interval, setInterval] = useState(Math.max(1, Math.floor(seconds / a.defaultFrameCount)));
  const frames = countMode ? Number(count) : Math.max(2, Math.min(2147483647, Math.floor(seconds / interval)));
  const sliceMs = countMode ? Math.floor((a.durationMs || 0) / Math.max(2, frames)) : interval * 1000;
  const loading = pdf && a.pagePreviewCount !== a.pageCount;
  const valid = pdf ? pages.length > 0 && !loading : Number.isInteger(frames) && frames >= 2 && frames <= 2147483647;
  function cancel() { if (editable) sync.attachmentCommand("attachment_remove", a.id); }
  useEffect(() => { dialog.current.showModal(); return () => dialog.current?.close(); }, []);
  return html`<dialog ref=${dialog} class="attachment-editor" aria-label=${pdf ? t.pdfTitle : t.videoTitle}
    onCancel=${event => { event.preventDefault(); cancel(); }} onClick=${event => { if (event.target === dialog.current) cancel(); }} onKeyDown=${event => event.stopPropagation()}>
    <section><h2>${pdf ? t.pdfTitle : t.videoTitle}</h2>
    ${pdf ? html`<p>${t.pdfSubtitle(a.pageCount)}</p><div class="attachment-editor-row"><span>${t.pagesSelected(pages.length)}</span>
      <button type="button" disabled=${loading || !editable} onClick=${() => setPages(pages.length === a.pageCount ? [] : Array.from({ length: a.pageCount }, (_, i) => i))}>${pages.length === a.pageCount ? t.deselectAll : t.selectAll}</button></div>
      ${loading ? html`<div class="pdf-loading"><span class="spinner"></span><p>${t.renderingPages(a.previewDone || 0, a.previewTotal || a.pageCount)}</p></div>` : html`
      <div class="pdf-grid">${Array.from({ length: a.pageCount }, (_, i) => html`<div class="pdf-page" data-selected=${pages.includes(i)} key=${i}>
        <button type="button" aria-label=${t.page(i + 1)} onClick=${() => onPreview(i)}><img src=${sync.attachmentUrl(a.id, "page", i)} alt=${t.page(i + 1)} /><span>${i + 1}</span></button>
        <input type="checkbox" aria-label=${t.page(i + 1)} checked=${pages.includes(i)} disabled=${!editable}
          onChange=${() => setPages(pages.includes(i) ? pages.filter(page => page !== i) : [...pages, i])} />
      </div>`)}</div>`}` : html`<p>${t.duration(`${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`)}</p>
      <video class="video-edit-preview" src=${sync.attachmentUrl(a.id, "source")} preload="metadata" muted></video>
      <div class="video-modes">${[true, false].map(mode => html`<button type="button" aria-pressed=${countMode === mode} onClick=${() => setCountMode(mode)}>${mode ? t.byFrameCount : t.byInterval}</button>`)}</div>
      ${countMode ? html`<label>${t.frames(frames || 2)}<input type="number" min="2" max="2147483647" value=${count} aria-invalid=${!valid} onInput=${event => setCount(event.currentTarget.value)} /></label><p>${t.betweenFrames(sliceMs < 1000 ? `${sliceMs}ms` : `${Math.round(sliceMs / 1000)}s`)}</p>`
        : html`<label>${t.interval(interval)}<input type="range" min="1" max=${Math.max(1, Math.min(seconds, 30))} value=${interval} onInput=${event => setInterval(Number(event.currentTarget.value))} /></label><p>${t.frames(frames)}</p>`}`}
    <footer><button type="button" disabled=${!editable} onClick=${cancel}>${t.cancel}</button>
      <button class="filled" type="button" disabled=${!editable || !valid} onClick=${() => sync.attachmentCommand(pdf ? "attachment_pdf" : "attachment_video", a.id,
        pdf ? { pages } : { frameCount: frames, intervalMs: sliceMs })}>${pdf ? t.sendPages(pages.length) : t.extractFrames(frames)}</button></footer>
    </section></dialog>`;
}

function AttachmentViewer({ viewer, onNavigate, onClose }) {
  const dialog = useRef(null);
  const index = viewer.index || 0;
  const [scale, setScale] = useState(1);
  const file = viewer.file;
  const item = viewer.items?.[index];
  const source = item && sync.attachmentUrl(item.id, item.kind, item.index);
  const [loadedSource, setLoadedSource] = useState(null);
  const [failedSource, setFailedSource] = useState(null);
  const loaded = !!source && loadedSource === source;
  const failed = !!source && failedSource === source;
  useEffect(() => { dialog.current.showModal(); return () => dialog.current?.close(); }, []);
  function navigate(next) { setScale(1); onNavigate(next); }
  return html`<dialog ref=${dialog} class=${`tool-media-viewer attachment-viewer ${file ? "text-file-viewer" : ""}`} aria-label=${file?.name || item?.name || t.attachments}
    onCancel=${event => { event.preventDefault(); onClose(); }} onKeyDown=${event => {
      event.stopPropagation();
      if (event.key === "ArrowLeft" && index > 0) navigate(index - 1);
      if (event.key === "ArrowRight" && index < (viewer.items?.length || 0) - 1) navigate(index + 1);
    }}>
    ${file ? html`<div class="text-file-content">${/\.(md|markdown)$/i.test(file.name || "") ? html`<${Markdown} text=${{ markdown: file.text, math: [] }} />` : html`<pre>${file.text}</pre>`}</div>`
      : item?.type === "video" && item.kind === "source" ? html`<video key=${source} src=${source} controls autoplay
        onLoadedData=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)} style=${{ opacity: loaded ? 1 : 0 }}></video>`
      : html`<div class="tool-media-scroll" onDblClick=${() => { if (loaded) setScale(scale === 1 ? 3 : 1); }}><img key=${source} class="attachment-full-image" src=${source}
        alt=${item.name || t.attachments} onLoad=${() => setLoadedSource(source)} onError=${() => setFailedSource(source)}
        style=${{ width: `${scale * 100}%`, height: `${scale * 100}%`, opacity: loaded ? 1 : 0 }} /></div>`}
    ${!file && html`<div class="attachment-viewport-status" style=${{ opacity: !loaded && !failed ? 1 : 0 }}><span class="spinner" role="status" aria-label=${t.attachments}></span></div>
      <div class="attachment-viewport-status" style=${{ opacity: failed ? 1 : 0 }} role=${failed ? "alert" : null}>${icon(item.type === "image" || item.kind === "page" ? ICON_BROKEN_IMAGE : ICON_ERROR)}</div>`}
    <div class="tool-media-controls"><span class="tool-media-count">${file?.name || `${index + 1} / ${viewer.items.length}`}</span>
      <button class="detail-sheet-icon" type="button" title=${t.close} aria-label=${t.close} onClick=${onClose}>${icon(ICON_CLOSE)}</button></div>
    ${!file && viewer.items.length > 1 && html`<button class="tool-media-previous detail-sheet-icon" type="button" aria-label=${t.previous} disabled=${index === 0} onClick=${() => navigate(index - 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>
      <button class="tool-media-next detail-sheet-icon" type="button" aria-label=${t.next} disabled=${index === viewer.items.length - 1} onClick=${() => navigate(index + 1)}>${icon(ICON_CHEVRON_RIGHT)}</button>`}
  </dialog>`;
}
