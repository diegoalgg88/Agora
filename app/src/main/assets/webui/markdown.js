// Assistant Markdown, laid out like the app's MessageItemMarkdown on the mikepenz renderer. The
// phone already split math out with the app's own parser: a formula is "\uE000<index>\uE001" in
// the text and its TeX is in `math`. Raw HTML stays literal text, as in the app, and KaTeX builds
// its DOM directly so the page needs no inline style attributes.
import { useLayoutEffect, useMemo, useRef, useState } from "./vendor/preact-hooks.mjs";
import { Marked, Renderer } from "./vendor/marked.esm.js";
import { html } from "./html.js";
import { t } from "./i18n.js";
import { ICON_CONTENT_COPY } from "./icons.js";

const escapeHtml = (text) =>
  text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const SAFE_LINK = /^(https?:|mailto:)/i;
// Nested lists indent 8 dp per level; style.css has one rule per level up to this depth.
const MAX_LIST_DEPTH = 6;
let listDepth = 0;

const marked = new Marked({ gfm: true });
const sourceAttributes = token => `data-source-positions="${token.sourcePositions?.join(",") ?? ""}" data-source-raw="${escapeHtml(token.raw)}"`;
marked.use({
  renderer: {
    text(token) {
      if (token.tokens) return this.parser.parseInline(token.tokens);
      return `<span ${sourceAttributes(token)}>${Renderer.prototype.text.call(this, token)}</span>`;
    },
    codespan(token) {
      return `<code ${sourceAttributes(token)}>${escapeHtml(token.text)}</code>`;
    },
    html: token => `<span ${sourceAttributes(token)} data-source-literal="true">${escapeHtml(token.text)}</span>`,
    link({ href, title, tokens }) {
      const label = this.parser.parseInline(tokens);
      if (!SAFE_LINK.test(href)) return label;
      const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
      return `<a href="${escapeHtml(href)}"${titleAttr} target="_blank" rel="noopener noreferrer">${label}</a>`;
    },
    // MarkdownListItems: the marker ("• " or "<n>. ") beside a column that holds the item.
    list(token) {
      const depth = Math.min(listDepth, MAX_LIST_DEPTH);
      listDepth += 1;
      const items = token.items.map((item, index) => {
        const marker = token.ordered ? `${token.start + index}. ` : "• ";
        return `<li><span class="md-marker">${marker}</span>` +
          `<div class="md-item">${this.parser.parse(item.tokens)}</div></li>`;
      }).join("");
      listDepth -= 1;
      const tag = token.ordered ? "ol" : "ul";
      return `<${tag} class="md-list" data-depth="${depth}">${items}</${tag}>`;
    },
    // ChatCodeBlockHeader: the language (when given) and Copy in a SpaceBetween row, then the code.
    code(token) {
      const { text, lang, raw } = token;
      const language = (lang ?? "").trim().split(/\s+/)[0];
      const label = language ? `<span class="code-language">${escapeHtml(language.toUpperCase())}</span>` : "";
      return `<div class="code-block"><div class="code-header">${label}` +
        `<button class="code-copy" type="button" aria-label="${escapeHtml(t.copy)}"></button></div>` +
        `<pre><code ${sourceAttributes(token)} data-source-content-start="${/^\s*(```|~~~)/.test(raw) ? raw.indexOf("\n") + 1 : 0}">${escapeHtml(text)}</code></pre></div>`;
    },
  },
});

const MATH_SLOT = /\uE000(\d+)\uE001/g;
const SPACER = '<div class="md-spacer"></div>';
const lineBreaks = (text) => text.split("\n").length - 1;

/**
 * The app puts one block spacer before every top-level node, and each line break between blocks
 * is a node of its own, so a blank line between two paragraphs is three spacers. Display math
 * is its own paragraph with a blank line on each side, as latexToMarkdown writes it.
 */
function renderHtml(markdown, math, sourceMap = [], citations = []) {
  const positions = [];
  let source = "", boundary = 0, delta = 0;
  for (let index = 0; index < markdown.length; index++) {
    while (sourceMap[boundary]?.[0] <= index) {
      delta = sourceMap[boundary][1] - sourceMap[boundary][0];
      boundary++;
    }
    const slot = markdown[index] === "\uE000" && markdown.slice(index).match(/^\uE000(\d+)\uE001/);
    const display = slot && math?.[slot[1]]?.display;
    if (display) { source += "\n\n"; positions.push(index + delta, index + delta); }
    source += markdown[index];
    positions.push(index + delta);
    if (display) {
      for (let part = 1; part < slot[0].length; part++) {
        index++;
        while (sourceMap[boundary]?.[0] <= index) {
          delta = sourceMap[boundary][1] - sourceMap[boundary][0];
          boundary++;
        }
        source += markdown[index];
        positions.push(index + delta);
      }
      source += "\n\n"; positions.push(index + delta, index + delta);
    }
  }
  const tokens = marked.lexer(source);
  function locate(tokens, raw, positions) {
    let cursor = 0;
    for (const token of tokens) {
      const exact = raw.indexOf(token.raw, cursor);
      const selected = [];
      for (let index = 0; index < token.raw.length; index++) {
        const found = exact >= 0 ? exact + index : raw.indexOf(token.raw[index], cursor);
        if (found < 0) break;
        selected.push(positions[found]);
        cursor = found + 1;
      }
      token.sourcePositions = selected;
      locate(token.tokens || token.items || [], token.raw, selected);
      if (token.header) {
        locate([...token.header, ...token.rows.flat()].flatMap(cell => cell.tokens), token.raw, selected);
      }
    }
  }
  locate(tokens, source, positions);
  let out = "";
  let breaks = 0;
  for (const token of tokens) {
    if (token.type === "space") {
      breaks += lineBreaks(token.raw);
      continue;
    }
    listDepth = 0;
    out += SPACER.repeat(breaks + 1) + marked.parser(Object.assign([token], { links: tokens.links }));
    breaks = token.raw.length - token.raw.replace(/\n+$/, "").length;
  }
  out += SPACER.repeat(breaks);
  // Expand only text nodes: source attributes must retain the original token verbatim.
  const template = document.createElement("template");
  template.innerHTML = out;
  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  const citationByToken = new Map(citations.map(marker => [marker.token, marker]));
  for (const node of nodes) {
    if (node.parentElement?.closest("code,pre")) continue;
    const matches = [...node.data.matchAll(MATH_SLOT)].filter(match => math?.[Number(match[1])]);
    for (let index = 0; index < node.data.length; index++) {
      const citation = citationByToken.get(node.data[index]);
      if (citation) matches.push({ index, 0: citation.token, citation });
    }
    matches.sort((a, b) => a.index - b.index);
    if (!matches.length) continue;
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    const appendText = end => {
      if (end <= cursor) return;
      const span = document.createElement("span");
      span.dataset.sourceTextStart = String(cursor);
      span.textContent = node.data.slice(cursor, end);
      fragment.append(span);
    };
    for (const match of matches) {
      appendText(match.index);
      const slot = document.createElement(match.citation ? "button" : "span");
      if (match.citation) {
        const marker = match.citation;
        slot.className = "citation-inline";
        slot.type = "button";
        slot.dataset.citationToken = marker.token;
        slot.setAttribute("aria-label", marker.accessibilityLabel || (marker.sources.length > 1 ? `${marker.label} +${marker.sources.length - 1}` : marker.sources[0].title));
        const alternate = document.createElement("span");
        alternate.className = "citation-alternate";
        alternate.textContent = `[${marker.label}${marker.sources.length > 1 ? ` +${marker.sources.length - 1}` : ""}]`;
        slot.append(alternate);
        const label = document.createElement("span"); label.className = "citation-primary"; label.textContent = marker.label; slot.append(label);
        if (marker.sources.length > 1) {
          const suffix = document.createElement("span"); suffix.className = "citation-suffix";
          suffix.textContent = `+${marker.sources.length - 1}`; slot.append(suffix);
        }
      } else {
        slot.className = "math";
        slot.dataset.math = match[1];
      }
      fragment.append(slot);
      cursor = match.index + match[0].length;
    }
    appendText(node.data.length);
    node.replaceWith(fragment);
  }
  return template.innerHTML;
}

const copyIcon = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON_CONTENT_COPY}"/></svg>`;
let fadeIdentity = 0;

/** Draw-only alpha preserves the text nodes, shaping, wrapping, selection and search geometry. */
export function fadeStreamingText(element, streaming, reduceMotion, timeline, sourceStart = 0, variant = "answer", tailCodePoints = null, spatialBands = 0, initialAlpha = 0) {
  if (!element || reduceMotion || (tailCodePoints != null && !streaming)) return;
  const published = performance.now();
  const groups = new Map();
  const count = [...element.textContent].length;
  const fadedCount = tailCodePoints == null ? count : Math.min(count, tailCodePoints);
  const prefix = count - fadedCount;
  const bands = Math.min(spatialBands, fadedCount);
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement.closest(".code-header,.md-marker,.math,.citation-inline")
      ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  let index = sourceStart;
  while (walker.nextNode()) {
    const node = walker.currentNode;
    let offset = 0, color;
    for (const glyph of node.textContent) {
      const key = `${variant}:${index++}`;
      let birth = timeline.get(key);
      if (birth == null && streaming) { birth = published; timeline.set(key, birth); }
      const suffix = index - sourceStart - 1 - prefix;
      const spatial = bands > 0 ? 1 - (Math.floor(suffix * bands / fadedCount) + 1) / bands * (1 - initialAlpha) : initialAlpha;
      if (birth != null && suffix >= 0 && published - birth < 500) {
        color ??= getComputedStyle(node.parentElement).color;
        const groupKey = `${birth}:${color}:${spatial}`;
        let group = groups.get(groupKey);
        if (!group) { group = { birth, color, spatial, ranges: [] }; groups.set(groupKey, group); }
        const range = new Range();
        range.setStart(node, offset);
        range.setEnd(node, offset + glyph.length);
        group.ranges.push(range);
      }
      offset += glyph.length;
    }
  }
  if (!groups.size) return;
  const sheet = new CSSStyleSheet();
  document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  const owned = [...groups.values()].map(group => {
    const name = `agora-stream-${++fadeIdentity}`;
    const highlight = new Highlight();
    for (const range of group.ranges) highlight.add(range);
    CSS.highlights.set(name, highlight);
    const rule = sheet.cssRules[sheet.insertRule(`::highlight(${name}) { color: transparent; }`)];
    return { ...group, name, rule };
  });
  let frame = 0;
  const paint = () => {
    const now = performance.now();
    let active = false;
    for (const group of owned) {
      const alpha = Math.min(1, Math.max(0, group.spatial + (now - group.birth) / 500));
      group.rule.style.color = `color-mix(in srgb, ${group.color} ${alpha * 100}%, transparent)`;
      active ||= alpha < 1;
    }
    if (active) frame = requestAnimationFrame(paint);
    else delete element.dataset.streamFading;
  };
  element.dataset.streamFading = "true";
  paint();
  return () => {
    delete element.dataset.streamFading;
    cancelAnimationFrame(frame);
    owned.forEach(group => CSS.highlights.delete(group.name));
    document.adoptedStyleSheets = document.adoptedStyleSheets.filter(current => current !== sheet);
  };
}
/** Projects server match identities onto visible glyphs without supplying result counts. */
export function highlightSearch(element, search, matches, sourceStart = 0) {
  if (!search?.query.trim() || !matches.length) return;
  const highlights = CSS.highlights.get("agora-search") ?? new Highlight();
  const active = CSS.highlights.get("agora-search-active") ?? new Highlight();
  CSS.highlights.set("agora-search", highlights);
  CSS.highlights.set("agora-search-active", active);
  const owned = [];
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement.closest(".code-header,.md-marker,.math,.citation-inline") ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
  });
  const nodes = [];
  let node;
  while ((node = walker.nextNode())) nodes.push({ node });
  for (const entry of nodes) {
    const token = entry.node.parentElement.closest("[data-source-positions]");
    if (!token && element.classList.contains("markdown")) continue;
    const raw = token?.dataset.sourceRaw ?? entry.node.textContent;
    const plainStart = Number(entry.node.parentElement.closest("[data-source-start]")?.dataset.sourceStart ?? 0);
    const positions = token ? token.dataset.sourcePositions.split(",").map(Number) :
      Array.from({ length: raw.length }, (_, index) => plainStart + index);
    const value = entry.node.textContent;
    const inlineCode = token?.tagName === "CODE" && !token.hasAttribute("data-source-content-start");
    const escapedText = token && token.tagName !== "CODE" && !token.hasAttribute("data-source-literal");
    let decoded = "", decodedPositions = [], cursor = Number(token?.dataset.sourceContentStart ?? 0);
    for (let index = cursor; index < raw.length; index++) {
      const entity = escapedText && raw.slice(index).match(/^&(?:#\d+|#x[\da-f]+|[a-z]+);/i);
      if (entity) {
        const span = document.createElement("span");
        span.innerHTML = entity[0];
        decoded += span.textContent;
        decodedPositions.push(...Array(span.textContent.length).fill(positions[index]));
        index += entity[0].length - 1;
      } else if (escapedText && raw[index] === "\\" && /[!"#$%&'()*+,\-./:;<=>?@[\]\\^_`{|}~]/.test(raw[index + 1] ?? "")) {
        continue;
      } else {
        // Only parsed inline code replaces line breaks; plain user text and literal HTML stay raw.
        decoded += inlineCode && raw[index] === "\n" ? " " : raw[index];
        decodedPositions.push(positions[index]);
      }
    }
    cursor = Number(entry.node.parentElement.closest("[data-source-text-start]")?.dataset.sourceTextStart ?? 0);
    for (let index = 0; index < value.length; index++) {
      const found = decoded.indexOf(value[index], cursor);
      if (found < 0) break;
      cursor = found + 1;
      const position = decodedPositions[found] + sourceStart;
      const match = matches.find(match => position >= match.start && position < match.endExclusive);
      if (!match) continue;
      entry.ranges ??= [];
      const previous = entry.ranges.at(-1);
      if (previous?.key === match.key && previous.end === index) previous.end++;
      else entry.ranges.push({ start: index, end: index + 1, key: match.key });
    }
  }
  for (const entry of nodes) {
    for (const match of entry.ranges ?? []) {
      const range = new Range();
      range.setStart(entry.node, match.start);
      range.setEnd(entry.node, match.end);
      range.searchKey = match.key;
      owned.push(range);
      highlights.add(range);
      if (search.matches[search.index]?.key === match.key) active.add(range);
    }
  }
  active.priority = 1;
  return () => {
    for (const range of owned) { highlights.delete(range); active.delete(range); }
    if (!highlights.size) {
      CSS.highlights.delete("agora-search");
      CSS.highlights.delete("agora-search-active");
    }
  };
}

/**
 * Renders one WebText. `variant` picks the answer or thought type scale; `wrap` follows the app's
 * code block wrapping setting (off scrolls long lines sideways).
 */
export function Markdown({ text, variant = "answer", wrap = true, search, matches = [], sourceStart = 0,
  streaming = false, reduceMotion = false, births, emptyStreamingText = null, onCitation }) {
  const root = useRef(null);
  const emptyNode = useRef(null);
  const showEmpty = streaming && !text.markdown.trim() && emptyStreamingText != null;
  const [retainedEmpty, setRetainedEmpty] = useState(showEmpty);
  useLayoutEffect(() => {
    const node = emptyNode.current;
    if (!node) return undefined;
    if (showEmpty) setRetainedEmpty(true);
    const animation = node.animate([{ opacity: getComputedStyle(node).opacity }, { opacity: showEmpty ? 1 : 0 }],
      { duration: 180, easing: "linear", fill: "both" });
    animation.onfinish = () => {
      node.style.opacity = showEmpty ? "1" : "0";
      animation.cancel();
      if (!showEmpty) setRetainedEmpty(false);
    };
    return () => {
      node.style.opacity = getComputedStyle(node).opacity;
      animation.cancel();
    };
  }, [showEmpty, emptyStreamingText]);
  const localBirths = useRef(new Map());
  const citationKey = JSON.stringify(text.citations ?? []);
  const markup = useMemo(() => renderHtml(text.markdown, text.math, text.sourceMap, text.citations), [text.markdown, text.math, text.sourceMap, citationKey]);
  const activateCitation = useRef(onCitation);
  activateCitation.current = onCitation;
  const mathKey = JSON.stringify(text.math ?? []);
  const searchKey = JSON.stringify([!!search?.query.trim(), matches.map(match =>
    [match.key, match.start, match.endExclusive])]);
  const activeMatch = search?.matches[search.index]?.key;
  // Semantic markup is the only body commit; parent refreshes preserve text and KaTeX nodes.
  useLayoutEffect(() => { root.current.innerHTML = markup; }, [markup]);
  useLayoutEffect(() => {
    root.current.querySelectorAll("[data-citation-token]").forEach(button => {
      button.onclick = () => {
        const marker = text.citations?.find(marker => marker.token === button.dataset.citationToken);
        if (marker) activateCitation.current?.(marker.sources);
      };
    });
  }, [markup, citationKey]);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element) return;
    element.querySelectorAll("[data-math]").forEach((slot) => {
      const math = text.math?.[Number(slot.dataset.math)];
      if (!math) return;
      slot.classList.toggle("display", math.display);
      window.katex?.render(math.tex, slot, { displayMode: math.display, throwOnError: false });
    });
    element.querySelectorAll(".code-copy").forEach((button) => {
      button.innerHTML = copyIcon;
      button.onclick = () => {
        const code = button.closest(".code-block")?.querySelector("code")?.textContent ?? "";
        navigator.clipboard?.writeText(code).catch(() => {});
      };
    });
  }, [markup, mathKey]);
  useLayoutEffect(() => highlightSearch(root.current, search, matches, sourceStart), [markup, searchKey, sourceStart]);
  useLayoutEffect(() => {
    const active = CSS.highlights.get("agora-search-active");
    for (const range of CSS.highlights.get("agora-search") ?? []) {
      if (!root.current.contains(range.startContainer)) continue;
      if (range.searchKey === activeMatch) active?.add(range);
      else active?.delete(range);
    }
  }, [markup, searchKey, sourceStart, activeMatch]);
  useLayoutEffect(() => {
    return fadeStreamingText(root.current, streaming, reduceMotion,
      births ?? localBirths.current, sourceStart, variant);
  }, [markup, searchKey, sourceStart, streaming, reduceMotion, births]);
  const className = `markdown ${variant}${wrap ? "" : " nowrap"}`;
  const content = html`<div class=${className} ref=${root} />`;
  return emptyStreamingText == null ? content : html`<div class="markdown-message">
    ${content}
    ${(showEmpty || retainedEmpty) && html`<div class=${`${className} markdown-empty-stream`}
      ref=${emptyNode} aria-hidden=${!showEmpty}>${emptyStreamingText}</div>`}
  </div>`;
}
