// Agora WebUI: sign-in shell (stage 2). Chat views arrive in later stages.
import { h, render } from "./vendor/preact.mjs";
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import htm from "./vendor/htm.mjs";

const html = htm.bind(h);

const TEXT = {
  en: {
    title: "Agora",
    signInHint: "Enter the WebUI password set in the Agora app.",
    password: "Password",
    signIn: "Sign in",
    signingIn: "Signing in…",
    connectedTitle: "Connected",
    connectedHint: "This browser is signed in to Agora. Chat controls arrive in a later update.",
    signOut: "Sign out",
    wrongPassword: (left) => `Wrong password. ${left} attempts left before a 5-minute lock.`,
    locked: (seconds) => `Too many attempts. Try again in ${Math.ceil(seconds / 60)} min.`,
    notConfigured: "No WebUI password is set in the app.",
    failed: "Could not reach Agora. Check that the phone is on the same network.",
  },
  zh: {
    title: "Agora",
    signInHint: "输入在 Agora App 里设置的 WebUI 密码。",
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

function errorText(status, body) {
  if (status === 401 && body?.error === "wrong_password") return t.wrongPassword(body.attemptsLeft);
  if (status === 429) return t.locked(body?.retryAfterSeconds ?? 300);
  if (status === 503) return t.notConfigured;
  return t.failed;
}

function SignIn({ onSignedIn }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError("");
    try {
      const response = await postJson("/api/login", { password });
      if (response.ok) {
        onSignedIn();
        return;
      }
      const body = await response.json().catch(() => null);
      setError(errorText(response.status, body));
    } catch {
      setError(t.failed);
    } finally {
      setBusy(false);
    }
  }

  return html`
    <form class="card" onSubmit=${submit}>
      <h1>${t.title}</h1>
      <p>${t.signInHint}</p>
      <label for="password">${t.password}</label>
      <input id="password" type="password" autocomplete="current-password" autofocus
        value=${password} onInput=${(e) => setPassword(e.currentTarget.value)} />
      ${error && html`<p class="error" role="alert">${error}</p>`}
      <button type="submit" disabled=${busy || !password}>${busy ? t.signingIn : t.signIn}</button>
    </form>`;
}

function Connected({ onSignedOut }) {
  async function signOut() {
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  return html`
    <section class="card">
      <h1>${t.connectedTitle}</h1>
      <p>${t.connectedHint}</p>
      <button class="secondary" type="button" onClick=${signOut}>${t.signOut}</button>
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
