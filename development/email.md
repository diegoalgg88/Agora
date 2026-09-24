# Email (IMAP/SMTP) Contract

Authoritative contract for Agora's email feature: connecting accounts over IMAP/SMTP,
background polling, AI reading tools, and user-gated sending. Implementation:
`email/` (protocol clients), `data/EmailStore.kt`, `data/EmailDraftStore.kt`,
`data/EmailPoller.kt`, `data/EmailAccountSettings.kt`, `tool/EmailToolProvider.kt`,
`automation/HeartbeatScheduler.kt` (poll + consume), `ui/settings/EmailSettingsSection.kt`,
`ui/chat/composables/PendingEmailDraftBanner.kt`.

## 1. State ownership

| State | Owner | Notes |
|---|---|---|
| Fetched messages, pending queue, per-account sync state (UID watermark), drafts | Room (`email_messages`, `email_pending`, `email_sync_state`, `email_drafts`) | Room is the single durable truth; no DataStore mirror exists, so the two can never drift. |
| Connected accounts, poll interval | DataStore via `EmailAccountSettings` | Account records never contain the password. |
| Account passwords | DataStore, encrypted with `SecretCrypto`, keyed by account id in a separate map | SecretCrypto falls back to plaintext when Keystore fails (existing app-wide behavior). |

Room schema v36 (migration `MIGRATION_35_36`, CREATE-only).

## 2. Identity and keys

- Every persisted row is keyed by the composite `(accountId, uid)`. A bare UID is
  never unique across accounts; all pending consumption, dedup, and deletion use
  the composite key (encoded `accountId:uid` in the DAO's IN clause).
- Account ids are UUIDs minted by `setup_email`.
- `EmailDraftStatus` is its own enum (PENDING/SENDING/SENT/FAILED), mirroring but
  not importing `SmsDraftStatus`.

## 3. Connecting accounts (`setup_email`)

- Server auto-detection covers Gmail, Outlook/Hotmail/Live, Yahoo, iCloud/me/mac,
  AOL, Zoho, Fastmail. **Proton is deliberately excluded**: its only IMAP path is a
  desktop-only Bridge on cleartext localhost, which cannot exist on Android.
- The IMAP login is probed before anything is persisted — a wrong App Password
  must never leave a dead account behind.
- Account setup happens in chat (the tool guides the user to App Passwords);
  the Settings page only manages/removes existing accounts.

## 4. Watermark semantics (polling)

- Per-account `lastSeenUid` in `email_sync_state`. Delivery is watermark-based,
  NOT provider-flag-based: a message counts as "new" when `uid > lastSeenUid`.
- **First-poll seed**: the first poll after connect (watermark 0) records the max
  existing unseen UID and enqueues nothing — inbox history is never dumped into
  the pending queue.
- The poller filters `uid > lastSeenUid && uid !in pendingUids`, takes at most
  `MAX_FETCH_PER_POLL = 50`, ascending. Empty fetch results still advance the
  watermark to the batch max so unfetchable UIDs don't loop forever.
- Poll failures persist `lastError` + `lastAttemptEpochMs` and never touch the
  watermark; the scheduler's interval gate uses `max(lastSync, lastAttempt)`
  per account, so a failing account backs off without blocking healthy ones.
- One failed account never aborts the poll of other accounts.

## 5. Delivery (heartbeat + `check_email`)

- Polls enqueue into `email_pending`; the heartbeat renders up to 20 (newest
  first) in its `## New Emails` section.
- **Consume-on-success only**: after a successful heartbeat run, exactly the
  snapshot keys the AI saw are removed and each account's watermark advances to
  the snapshot's max UID — so the user's own `check_email` never repeats what the
  heartbeat already showed. A failed run (or messages arriving during the call)
  survive to the next heartbeat.
- **Delivery cap semantics (decision P-2)**: batches larger than 20 deliver the
  20 most recent; messages between watermark positions are NOT lost — they remain
  in the inbox and findable via `search_email`.

## 6. Sending is staged, never direct

- `send_email` / `reply_email` only insert an `EmailDraft` (PENDING) via
  `EmailDraftStore` and answer that the user must confirm. The AI cannot dispatch
  mail. The **user's Send tap in the review banner is the only path** that runs
  SMTP (PENDING → SENDING → SENT/FAILED, cap 20 drafts).
- Fail-closed: missing account or password records FAILED with a reason; drafts
  not in PENDING are never re-sent.
- Sent copy: filed over IMAP into the account's configured folder → server
  SPECIAL-USE `\Sent` (RFC 6154) → common names → CREATE as last resort. APPEND
  is always attempted with the real message (a rejected mailbox leaves no trace).
  Gmail skips the IMAP copy entirely (its SMTP already files Sent).
- Replies thread `In-Reply-To`/`References` from the original's Message-ID and
  reuse the subject with an idempotent `Re:` prefix.

## 7. Tool gating

- `setup_email` is always visible (it must guide connection).
- All account tools (`check_email`, `read_email`, `search_email`, `send_email`,
  `reply_email`) are invisible to the model until ≥ 1 account is connected —
  same pattern as SMS tools.
- `read_email` reads Room first (last fetched body) and falls back to a live IMAP
  fetch; `mark_read` defaults to false — reading is non-destructive.
- Account resolution accepts account_id or email address, with a single-account
  fallback; ambiguity returns the connected-account list so the model can
  self-correct.

## 8. Secret hygiene (decision P-5)

- **No tool result, exception message, or log may ever contain the password.**
  `ImapClient`/`SmtpClient` build exceptions from server responses and connection
  state only — never from the sent command line. `EmailToolProvider` never
  interpolates the password argument into output strings.
- Portable archives never carry passwords: accounts export without them;
  `NativeBackupSecrets.emailPasswords` (the secrets entry) is the only password
  transport, and restore drops records without a matching account with a warning.
- Account removal cascades: password + sync state + pending + messages for that
  account, in transactions.

## 9. Portability

- `emailAccounts` + `emailPollIntervalMinutes` are portable settings; restore
  merges accounts by id (REPLACE clears absent accounts, cascading passwords),
  and the locally stored password is read before each upsert so the
  archive (which carries none) never wipes it.

## 10. Required test coverage

- ImapClient/SmtpClient protocol parsing (tagged commands, LIST SPECIAL-USE,
  APPEND literal sizing, dot-stuffing, MIME QP/base64/HTML fallback, header
  unfolding, RFC 5322 dates) — `ImapClientTest`, `SmtpClientTest`.
- Watermark boundaries: seed, dedup vs pending, per-account uid collision, cap
  50, failure without watermark movement — `EmailPollerTest`.
- Draft lifecycle: transitions, fail-closed, Gmail skip, staging-never-sends —
  `EmailDraftStoreTest`, `EmailToolProviderTest`.
- Password-echo: marked password must not appear in any result/exception —
  `EmailToolProviderTest` (4 cases, success + login failure + connect exception).
- Heartbeat section rendering: presence/omission, cap 20, sort order —
  `HeartbeatPromptBuilderTest`.
