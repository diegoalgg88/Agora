// Agora WebUI: sign-in shell (stage 2), styled after the app's Material 3 theme. Chat views arrive in later stages.
import { h, render } from "./vendor/preact.mjs";
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import htm from "./vendor/htm.mjs";

const html = htm.bind(h);

const TEXT = {
  en: {
    title: "Agora",
    signInTitle: "Sign In",
    signInHint: "Enter the WebUI password set in the Agora app.",
    show: "Show",
    hide: "Hide",
    password: "Password",
    signIn: "Sign In",
    signingIn: "Signing in…",
    connectedTitle: "Connected",
    connectedHint: "This browser is signed in to Agora. Chat controls arrive in a later update.",
    signOut: "Sign Out",
    wrongPassword: (left) => `Wrong password. ${left} attempts left before a 5-minute lock.`,
    locked: (seconds) => `Too many attempts. Try again in ${Math.ceil(seconds / 60)} min.`,
    notConfigured: "No WebUI password is set in the app.",
    failed: "Could not reach Agora. Check that the phone is on the same network.",
  },
  zh: {
    title: "Agora",
    signInTitle: "登录",
    signInHint: "输入在 Agora App 里设置的 WebUI 密码。",
    show: "显示",
    hide: "隐藏",
    password: "密码",
    signIn: "登录",
    signingIn: "正在登录…",
    connectedTitle: "已连接",
    connectedHint: "这个浏览器已登录 Agora。聊天功能会在后续更新中加入。",
    signOut: "退出登录",
    wrongPassword: (left) => `密码错误。再错 ${left} 次将锁定 5 分钟。`,
    locked: (seconds) => `尝试次数过多，请 ${Math.ceil(seconds / 60)} 分钟后再试。`,
    notConfigured: "App 里还没有设置 WebUI 密码。",
    failed: "连不上 Agora。请确认手机和这台设备在同一网络。",
  },
};

const lang = navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en";
const t = TEXT[lang];
document.documentElement.lang = lang;

async function postJson(path, body) {
  return fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
}

// Material Symbols paths (Apache 2.0), as the app's icons.
const ICON_VISIBILITY = "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
const ICON_VISIBILITY_OFF = "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z";
const ICON_WEB = "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-5 14H4v-4h11v4zm0-5H4V9h11v4zm5 5h-4V9h4v9z";

const icon = (d) => html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d=${d} /></svg>`;

function Brand() {
  return html`<div class="brand">${icon(ICON_WEB)}<span>${t.title}</span></div>`;
}

/** Field error for a wrong password; banner text for everything else. */
function describeError(status, body) {
  if (status === 401 && body?.error === "wrong_password") {
    return { field: t.wrongPassword(body.attemptsLeft) };
  }
  if (status === 429) return { banner: t.locked(body?.retryAfterSeconds ?? 300) };
  if (status === 503) return { banner: t.notConfigured };
  return { banner: t.failed };
}

function SignIn({ onSignedIn }) {
  const [password, setPassword] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState({});

  async function submit(event) {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError({});
    try {
      const response = await postJson("/api/login", { password });
      if (response.ok) {
        onSignedIn();
        return;
      }
      const body = await response.json().catch(() => null);
      setError(describeError(response.status, body));
    } catch {
      setError({ banner: t.failed });
    } finally {
      setBusy(false);
    }
  }

  return html`
    <form class="card" onSubmit=${submit}>
      <${Brand} />
      <h1>${t.signInTitle}</h1>
      <p>${t.signInHint}</p>
      ${error.banner && html`<div class="banner" role="alert">${error.banner}</div>`}
      <div class=${error.field ? "field invalid" : "field"}>
        <input id="password" type=${visible ? "text" : "password"} autocomplete="current-password"
          autofocus placeholder=" " aria-invalid=${error.field ? "true" : "false"}
          aria-describedby="password-supporting"
          value=${password} onInput=${(e) => { setPassword(e.currentTarget.value); setError({}); }} />
        <label for="password">${t.password}</label>
        <button class="icon-button" type="button" onClick=${() => setVisible(!visible)}
          aria-label=${visible ? t.hide : t.show}>
          ${icon(visible ? ICON_VISIBILITY_OFF : ICON_VISIBILITY)}
        </button>
      </div>
      <div class="supporting" id="password-supporting" role=${error.field ? "alert" : null}>
        ${error.field ?? ""}
      </div>
      <div class="actions">
        <button class="button filled" type="submit" disabled=${busy || !password}
          aria-label=${busy ? t.signingIn : null}>
          ${busy ? html`<span class="spinner" aria-hidden="true"></span>` : t.signIn}
        </button>
      </div>
    </form>`;
}

function Connected({ onSignedOut }) {
  async function signOut() {
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  return html`
    <section class="card">
      <${Brand} />
      <h1>${t.connectedTitle}</h1>
      <p>${t.connectedHint}</p>
      <div class="actions">
        <button class="button text" type="button" onClick=${signOut}>${t.signOut}</button>
      </div>
    </section>`;
}
function App() {
  // null while the session check is in flight, so neither screen flashes.
  const [signedIn, setSignedIn] = useState(null);
  useEffect(() => {
    fetch("/api/session", { credentials: "same-origin" })
      .then((r) => r.json())
      .then((body) => setSignedIn(body.signedIn === true))
      .catch(() => setSignedIn(false));
  }, []);
  if (signedIn === null) return null;
  return signedIn
    ? html`<${Connected} onSignedOut=${() => setSignedIn(false)} />`
    : html`<${SignIn} onSignedIn=${() => setSignedIn(true)} />`;
}

render(html`<${App} />`, document.getElementById("app"));
