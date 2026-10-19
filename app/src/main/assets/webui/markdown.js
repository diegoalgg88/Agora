// Assistant Markdown, laid out like the app's MessageItemMarkdown on the mikepenz renderer. The
// phone already split math out with the app's own parser: a formula is "\uE000<index>\uE001" in
// the text and its TeX is in `math`. Raw HTML stays literal text, as in the app, and KaTeX builds
// its DOM directly so the page needs no inline style attributes.
import { useLayoutEffect, useMemo, useRef } from "./vendor/preact-hooks.mjs";
import { Marked } from "./vendor/marked.esm.js";
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
marked.use({
  renderer: {
    html: ({ text }) => escapeHtml(text),
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
    code({ text, lang }) {
      const language = (lang ?? "").trim().split(/\s+/)[0];
      const label = language ? `<span class="code-language">${escapeHtml(language.toUpperCase())}</span>` : "";
      return `<div class="code-block"><div class="code-header">${label}` +
        `<button class="code-copy" type="button" aria-label="${escapeHtml(t.copy)}"></button></div>` +
        `<pre><code>${escapeHtml(text)}</code></pre></div>`;
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
function renderHtml(markdown, math) {
  const source = markdown.replace(MATH_SLOT, (slot, index) => (math?.[index]?.display ? `\n\n${slot}\n\n` : slot));
  const tokens = marked.lexer(source);
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
  return out.replace(MATH_SLOT, (_, index) => `<span class="math" data-math="${index}"></span>`);
}

const copyIcon = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${ICON_CONTENT_COPY}"/></svg>`;

/**
 * Renders one WebText. `variant` picks the answer or thought type scale; `wrap` follows the app's
 * code block wrapping setting (off scrolls long lines sideways).
 */
export function Markdown({ text, variant = "answer", wrap = true }) {
  const root = useRef(null);
  const markup = useMemo(() => renderHtml(text.markdown, text.math), [text.markdown, text.math]);
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
  }, [markup, text.math]);
  const className = `markdown ${variant}${wrap ? "" : " nowrap"}`;
  return html`<div class=${className} ref=${root} dangerouslySetInnerHTML=${{ __html: markup }} />`;
}
