# Email

Agora can connect your email accounts over IMAP/SMTP so the assistant can check your
inbox, read and search messages, and draft replies — while every outgoing email waits
for your explicit approval.

## Connecting an account

Account setup happens in chat. Just ask:

> "Connect my email" (and provide the address + an App Password when asked)

The assistant auto-detects the servers for Gmail, Outlook, Hotmail, Live, Yahoo,
iCloud, AOL, Zoho, and Fastmail, and **tests the login before saving anything** — a
wrong password never leaves a broken account behind.

### App Passwords

Most providers reject your regular account password for IMAP and require an
**App Password** instead:

| Provider | Where to create it |
|---|---|
| Gmail | myaccount.google.com → Security → 2-Step Verification → **App passwords** (2FA required) |
| Outlook / Hotmail / Live | account.microsoft.com → Security → **App passwords** |
| Yahoo | Account Security → **Generate app password** |
| iCloud / Me / Mac | appleid.apple.com → Sign-In and Security → **App-Specific Passwords** |
| AOL | Account Security → **App passwords** |
| Zoho | Zoho Accounts → Security → **Application-specific passwords** |
| Fastmail | Settings → Privacy & Security → **App passwords** |
| Other | Ask in chat and provide the IMAP/SMTP host and ports manually |

Proton is not supported: its only IMAP access requires a desktop-only Bridge, which
does not exist on Android.

## Reading email

Once connected, the assistant has these tools:

- **check_email** — lists messages the heartbeat hasn't shown yet (sender, subject,
  date, preview).
- **read_email** — fetches the full body of a specific message. Reading never marks
  anything as read on the server unless you ask (`mark_read` defaults to false).
- **search_email** — searches the INBOX by sender, subject, or date.

## Background polling & the heartbeat

If [Automation](automation.md) is enabled, Agora polls your inbox in the background
(every 15 minutes by default; configurable in **Settings → Automation → Email**, or
"Never" to disable).

- The **first poll after connecting starts from the newest message** — your existing
  inbox is never dumped into the assistant.
- New messages are delivered to the heartbeat conversation in a `## New Emails`
  section, summarized briefly; only items that genuinely need attention are flagged.
- Poll failures back off automatically and never lose messages.

## Sending: drafts first, always

The assistant **cannot send email by itself**. `send_email` and `reply_email` only
stage a draft, which appears in a review banner at the top of the chat:

- **Send** — dispatches the email over SMTP and files a copy into your Sent folder
  (Gmail does this automatically).
- **Discard** — deletes the draft. Nothing was ever sent.

Replies thread correctly: `In-Reply-To`/`References` headers and the `Re:` subject are
derived from the original message. Failed sends keep the draft with the error reason
so you can retry.

## Managing accounts

**Settings → Automation → Email** shows connected accounts, the poll interval, a
manual refresh action, and per-account removal. Removing an account deletes its
stored messages, pending items, and the saved password from this device.

## Portability & privacy

- Account settings (without passwords) and the poll interval are included in
  `.agora` archives. Passwords are exported only through the archive's opt-in
  secrets category, exactly like provider API keys.
- Passwords are stored encrypted with the Android Keystore (see
  [Privacy & Security](privacy.md)).
- Email content stays in Agora's local storage; conversations with the assistant
  about your email are processed with whichever model you use, like any other chat.
