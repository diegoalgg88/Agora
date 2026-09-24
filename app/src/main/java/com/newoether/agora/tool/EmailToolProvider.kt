package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.EmailAccount
import com.newoether.agora.data.EmailDraftStore
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.email.ImapClient
import com.newoether.agora.email.ServerAutoDetect
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tool provider for email (IMAP/SMTP) operations.
 * Read tools: setup_email, check_email, read_email, search_email
 * Send tools (F5-T2): send_email, reply_email — staged as drafts, never sent directly.
 *
 * Registration is gated per request in [definitions]: account tools only when at
 * least one email account is connected; setup_email stays visible so the model
 * can guide the user through connecting. Security invariant (decision P-5): no
 * tool result, exception message, or log ever contains the account password —
 * ImapClient builds errors from server responses only, and this provider never
 * interpolates the password argument into any output string.
 */
class EmailToolProvider(
    private val emailStore: EmailStore,
    private val emailDraftStore: EmailDraftStore,
    private val settingsRepository: SettingsRepository,
    private val settingsManager: SettingsManager,
    private val imapClientFactory: (EmailAccount) -> ImapClient = { account ->
        ImapClient(account.imapHost, account.imapPort, tls = true)
    },
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }

    private val toolNames = setOf(
        "setup_email", "check_email", "read_email", "search_email", "send_email", "reply_email",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        val setupTools = SETUP_TOOL_DEFINITIONS
        val accountTools = if (settingsRepository.emailAccounts.value.isNotEmpty()) {
            READ_TOOL_DEFINITIONS + SEND_TOOL_DEFINITIONS
        } else {
            emptyList()
        }
        return setupTools + accountTools
    }

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val argsStr = arguments.ifBlank { "{}" }
        val args = json.decodeFromString<Map<String, JsonElement>>(argsStr)
        fun arg(key: String): String? = (args[key] as? JsonPrimitive)?.content
        fun argLong(key: String): Long? = (args[key] as? JsonPrimitive)?.content?.toLongOrNull()
        fun argBool(key: String): Boolean? = (args[key] as? JsonPrimitive)?.content?.let {
            it.equals("true", ignoreCase = true)
        }

        when (name) {
            "setup_email" -> setupEmail(arg("email"), arg("password"), arg("imapHost"), argLong("imapPort")?.toInt(), arg("smtpHost"), argLong("smtpPort")?.toInt())
            "check_email" -> checkEmail()
            "read_email" -> readEmail(arg("account"), argLong("uid"), argBool("mark_read"))
            "search_email" -> searchEmail(arg("account"), arg("from"), arg("subject"), arg("since"))
            "send_email" -> sendEmail(arg("account"), arg("to"), arg("subject"), arg("body"))
            "reply_email" -> replyEmail(arg("account"), argLong("uid"), arg("body"))
            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in toolNames

    /** Connects a new account: auto-detects servers, tests the IMAP login before persisting. */
    private suspend fun setupEmail(
        email: String?,
        password: String?,
        imapHost: String?,
        imapPort: Int?,
        smtpHost: String?,
        smtpPort: Int?,
    ): String {
        if (email.isNullOrBlank()) return "Missing required argument: email"
        if (!email.contains("@")) return "Invalid email address: $email"
        if (password.isNullOrBlank()) return "Missing required argument: password"

        val detected = ServerAutoDetect.detect(email)
        val note = detected?.note?.let { "\n\nNote: $it" } ?: ""
        val resolvedImapHost = imapHost ?: detected?.imapHost
        val resolvedSmtpHost = smtpHost ?: detected?.smtpHost
        if (resolvedImapHost.isNullOrBlank() || resolvedSmtpHost.isNullOrBlank()) {
            return "Could not detect mail servers for ${email.substringAfter('@')}. " +
                "Provide imapHost and smtpHost explicitly."
        }
        val resolvedImapPort = imapPort ?: detected?.imapPort ?: 993
        val resolvedSmtpPort = smtpPort ?: detected?.smtpPort ?: 587
        val useStartTls = detected?.useStartTls ?: true

        // Test the IMAP login before anything is persisted — a wrong App Password
        // must never leave a dead account behind.
        val probeAccount = EmailAccount(
            id = "probe",
            email = email,
            imapHost = resolvedImapHost,
            imapPort = resolvedImapPort,
            smtpHost = resolvedSmtpHost,
            smtpPort = resolvedSmtpPort,
            useStartTls = useStartTls,
        )
        val probe = imapClientFactory(probeAccount)
        try {
            probe.connect()
            val ok = probe.login(email, password)
            if (!ok) {
                return "IMAP login failed for $email. The server rejected the credentials — " +
                    "most providers require an App Password (not the regular account password).$note"
            }
        } catch (e: Exception) {
            // Exception text carries the server/host state, never the password.
            return "Could not reach the IMAP server $resolvedImapHost:$resolvedImapPort: ${e.message}"
        } finally {
            runCatching { probe.logout() }
        }

        val account = EmailAccount(
            id = java.util.UUID.randomUUID().toString(),
            email = email,
            imapHost = resolvedImapHost,
            imapPort = resolvedImapPort,
            smtpHost = resolvedSmtpHost,
            smtpPort = resolvedSmtpPort,
            useStartTls = useStartTls,
        )
        settingsManager.emailAccountSettings.addAccount(account, password)
        return "Email account connected: $email\n" +
            "IMAP: $resolvedImapHost:$resolvedImapPort, SMTP: $resolvedSmtpHost:$resolvedSmtpPort\n" +
            "account_id: ${account.id}$note"
    }

    /** Lists the pending queue — emails the heartbeat has not shown the AI yet. */
    private suspend fun checkEmail(): String {
        val pending = emailStore.getPendingSnapshot()
        if (pending.isEmpty()) {
            return "No new emails. Use search_email to find a known message by sender or subject."
        }
        val accounts = settingsManager.emailAccountSettings.accountsOnce()
        val accountEmails = accounts.associate { it.id to it.email }
        return buildString {
            append("You have ${pending.size} new email(s) not yet shown:\n\n")
            for (msg in pending.takeLast(20).asReversed()) {
                append(
                    "Account: ${accountEmails[msg.accountId] ?: msg.accountId}\n" +
                        "UID: ${msg.uid}\nFrom: ${msg.fromAddress.ifBlank { "(unknown sender)" }}\n" +
                        "Subject: ${msg.subject.ifBlank { "(no subject)" }}\n" +
                        "Date: ${formatTimestamp(msg.dateEpochMs)}\nPreview: ${msg.preview}\n\n",
                )
            }
            append("Use read_email with the uid (and account) to fetch the full body.")
        }
    }

    /** Fetches the full body of one email by account + uid; Room first, IMAP fallback. */
    private suspend fun readEmail(accountRef: String?, uid: Long?, markRead: Boolean?): String {
        if (uid == null) return "Missing required argument: uid"
        val account = resolveAccount(accountRef)
            ?: return unresolvedAccountMessage(accountRef)

        val cached = emailStore.getMessageByUid(account.id, uid)
        val message = if (cached != null && cached.body.isNotBlank()) {
            cached
        } else {
            fetchBodyOverImap(account, uid) ?: cached
        } ?: return "No email found with uid $uid on ${account.email}."

        if (markRead == true) {
            markReadOverImap(account, uid)
        }

        return buildString {
            append("Account: ${account.email}\n")
            append("UID: $uid\n")
            append("From: ${message.fromAddress.ifBlank { "(unknown sender)" }}\n")
            append("Subject: ${message.subject.ifBlank { "(no subject)" }}\n")
            message.messageId?.let { append("Message-ID: $it\n") }
            append("Date: ${formatTimestamp(message.dateEpochMs)}\n")
            append("Read: ${if (message.isRead) "yes" else "no"}\n")
            message.listUnsubscribe?.takeIf { it.isNotBlank() }?.let { append("List-Unsubscribe: $it\n") }
            message.listUnsubscribePost?.takeIf { it.isNotBlank() }?.let { append("List-Unsubscribe-Post: $it\n") }
            append("\n${message.body}")
        }
    }

    /** Searches INBOX over IMAP by sender, subject, and/or date; newest 20 matches. */
    private suspend fun searchEmail(
        accountRef: String?,
        from: String?,
        subject: String?,
        since: String?,
    ): String {
        if (from.isNullOrBlank() && subject.isNullOrBlank() && since.isNullOrBlank()) {
            return "Provide at least one of: from, subject, since"
        }
        val account = resolveAccount(accountRef)
            ?: return unresolvedAccountMessage(accountRef)
        val password = settingsManager.emailAccountSettings.passwordFor(account.id)
            ?: return "Account credentials missing for ${account.email} — reconnect it via setup_email."

        val imap = imapClientFactory(account)
        try {
            imap.connect()
            if (!imap.login(account.email, password)) {
                return "IMAP login failed for ${account.email} — the stored credentials were rejected."
            }
            imap.selectInbox()
            val uids = buildList {
                when {
                    !from.isNullOrBlank() && !subject.isNullOrBlank() -> {
                        val byFrom = imap.searchByFrom(from).toSet()
                        addAll(imap.searchBySubject(subject).filter { it in byFrom })
                    }
                    !from.isNullOrBlank() -> addAll(imap.searchByFrom(from))
                    !subject.isNullOrBlank() -> addAll(imap.searchBySubject(subject))
                    else -> {}
                }
            }.ifEmpty {
                if (since.isNullOrBlank()) emptyList() else imap.searchSince(since)
            }
            if (uids.isEmpty()) {
                return "No emails found matching the given criteria on ${account.email}."
            }
            val limited = uids.takeLast(20)
            val messages = imap.fetchHeaders(limited, account.id)
            if (messages.isEmpty()) return "No readable emails matched on ${account.email}."

            return buildString {
                append("${messages.size} matching email(s) on ${account.email}:\n\n")
                for (msg in messages.asReversed()) {
                    append(
                        "UID: ${msg.uid}\nFrom: ${msg.fromAddress.ifBlank { "(unknown sender)" }}\n" +
                            "Subject: ${msg.subject.ifBlank { "(no subject)" }}\n" +
                            "Date: ${formatTimestamp(msg.dateEpochMs)}\nRead: ${if (msg.isRead) "yes" else "no"}\n" +
                            "Preview: ${msg.preview}\n\n",
                    )
                }
                append("Use read_email with the uid to fetch the full body.")
            }
        } finally {
            runCatching { imap.logout() }
        }
    }

    /** Stages a new outgoing email as a draft — nothing is sent until the user approves. */
    private suspend fun sendEmail(
        accountRef: String?,
        to: String?,
        subject: String?,
        body: String?,
    ): String {
        if (to.isNullOrBlank()) return "Missing required argument: to"
        if (!to.contains("@")) return "Invalid recipient address: $to"
        if (subject.isNullOrBlank()) return "Missing required argument: subject"
        if (body.isNullOrBlank()) return "Missing required argument: body"
        val account = resolveAccount(accountRef)
            ?: return unresolvedAccountMessage(accountRef)

        val draft = com.newoether.agora.data.EmailDraft(
            accountId = account.id,
            toAddress = to,
            subject = subject,
            body = body,
        )
        emailDraftStore.addDraft(draft)

        return "Email draft created from ${account.email} to $to:\n" +
            "Subject: $subject\n\"${body.take(200)}\"\n\n" +
            "User must confirm to send. A banner will appear in the chat UI."
    }

    /** Stages a reply: resolves the original message to thread In-Reply-To. */
    private suspend fun replyEmail(
        accountRef: String?,
        uid: Long?,
        body: String?,
    ): String {
        if (uid == null) return "Missing required argument: uid"
        if (body.isNullOrBlank()) return "Missing required argument: body"
        val account = resolveAccount(accountRef)
            ?: return unresolvedAccountMessage(accountRef)

        val original = emailStore.getMessageByUid(account.id, uid)
            ?: fetchBodyOverImap(account, uid)
            ?: return "No email found with uid $uid on ${account.email}."
        val originalSender = original.fromAddress.takeIf { it.isNotBlank() }
            ?: return "The original email has no sender address to reply to."
        val replySubject = if (original.subject.startsWith("Re:", ignoreCase = true)) {
            original.subject
        } else {
            "Re: ${original.subject.ifBlank { "(no subject)" }}"
        }

        val draft = com.newoether.agora.data.EmailDraft(
            accountId = account.id,
            toAddress = originalSender,
            subject = replySubject,
            body = body,
            inReplyToMessageId = original.messageId?.takeIf { it.isNotBlank() },
        )
        emailDraftStore.addDraft(draft)

        return "Reply draft created to $originalSender:\n" +
            "Subject: $replySubject\n\"${body.take(200)}\"\n\n" +
            "User must confirm to send. A banner will appear in the chat UI."
    }

    /** Resolves an account by id or email; single-account fallback when [accountRef] is null. */
    private suspend fun resolveAccount(accountRef: String?): EmailAccount? {
        val accounts = settingsManager.emailAccountSettings.accountsOnce()
        if (accounts.isEmpty()) return null
        if (accountRef.isNullOrBlank()) {
            return accounts.singleOrNull()
        }
        val lower = accountRef.lowercase()
        return accounts.firstOrNull { it.id == accountRef || it.email.lowercase() == lower }
    }

    private suspend fun unresolvedAccountMessage(accountRef: String?): String {
        val accounts = settingsManager.emailAccountSettings.accountsOnce()
        if (accounts.isEmpty()) {
            return "No email account is connected. Ask the user to connect one via setup_email."
        }
        val listed = accounts.joinToString("\n") { "- ${it.email} (account_id: ${it.id})" }
        return if (accountRef.isNullOrBlank()) {
            "Multiple accounts are connected — pass an 'account' (email or account_id):\n$listed"
        } else {
            "No account matches '$accountRef'. Connected accounts:\n$listed"
        }
    }

    private suspend fun fetchBodyOverImap(
        account: EmailAccount,
        uid: Long,
    ): com.newoether.agora.data.local.EmailMessageEntity? {
        val password = settingsManager.emailAccountSettings.passwordFor(account.id) ?: return null
        val imap = imapClientFactory(account)
        return try {
            imap.connect()
            if (!imap.login(account.email, password)) return null
            imap.selectInbox()
            val fetched = imap.fetchBody(uid, account.id) ?: return null
            com.newoether.agora.data.local.EmailMessageEntity(
                accountId = account.id,
                uid = uid,
                fromAddress = fetched.fromAddress,
                toAddress = fetched.toAddress,
                subject = fetched.subject,
                dateEpochMs = fetched.dateEpochMs,
                preview = fetched.preview,
                body = fetched.body,
                messageId = fetched.messageId,
                isRead = fetched.isRead,
                listUnsubscribe = fetched.listUnsubscribe.takeIf { it.isNotBlank() },
                listUnsubscribePost = fetched.listUnsubscribePost.takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { imap.logout() }
        }
    }

    private suspend fun markReadOverImap(account: EmailAccount, uid: Long) {
        val password = settingsManager.emailAccountSettings.passwordFor(account.id) ?: return
        val imap = imapClientFactory(account)
        try {
            imap.connect()
            if (imap.login(account.email, password)) {
                imap.selectInbox()
                imap.markAsRead(uid)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { imap.logout() }
        }
    }

    companion object {
        private val SETUP_TOOL_DEFINITIONS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "setup_email",
                    description = "Connect an email account via IMAP/SMTP using an App Password. " +
                        "Servers are auto-detected for Gmail, Outlook, Yahoo, iCloud, AOL, Zoho, and Fastmail; " +
                        "the IMAP login is tested before anything is saved. The user must provide an App " +
                        "Password (regular passwords are rejected by most providers).",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "email" to ToolProperty("string", "The user's full email address"),
                            "password" to ToolProperty("string", "The App Password for the account"),
                            "imapHost" to ToolProperty("string", "Optional: custom IMAP host (auto-detected for known providers)"),
                            "imapPort" to ToolProperty("integer", "Optional: custom IMAP port (default 993)"),
                            "smtpHost" to ToolProperty("string", "Optional: custom SMTP host"),
                            "smtpPort" to ToolProperty("integer", "Optional: custom SMTP port (default 587)"),
                        ),
                        required = listOf("email", "password"),
                    ),
                ),
            ),
        )

        private val READ_TOOL_DEFINITIONS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "check_email",
                    description = "List emails the user hasn't been shown yet (delivered by background polling). " +
                        "Returns account, uid, sender, subject, date, and a short preview. Use read_email with " +
                        "the uid to fetch the full body. If nothing is pending, use search_email to find known messages.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "read_email",
                    description = "Read the full body of a specific email by uid. Optional mark_read sets the " +
                        "\\Seen flag on the server (default: false — reading is non-destructive).",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "uid" to ToolProperty("integer", "The email uid returned by check_email or search_email"),
                            "account" to ToolProperty("string", "Account email or account_id (optional with a single connected account)"),
                            "mark_read" to ToolProperty("boolean", "Set the read flag on the server (default false)"),
                        ),
                        required = listOf("uid"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "search_email",
                    description = "Search the INBOX by sender, subject, and/or a date (since, format: 21-Sep-2026). " +
                        "Returns the newest 20 matches with uid, sender, subject, date, read flag, and preview.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "from" to ToolProperty("string", "Sender to match"),
                            "subject" to ToolProperty("string", "Subject text to match"),
                            "since" to ToolProperty("string", "Match messages since this date (e.g. 21-Sep-2026)"),
                            "account" to ToolProperty("string", "Account email or account_id (optional with a single connected account)"),
                        ),
                    ),
                ),
            ),
        )

        private val SEND_TOOL_DEFINITIONS = listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "send_email",
                    description = "Draft an outgoing email. The draft is staged in a banner at the top of the chat " +
                        "so the user must explicitly tap Send before anything is actually sent. You cannot bypass this — " +
                        "the tool only creates the draft. After calling, tell the user what you drafted and ask them to review.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "to" to ToolProperty("string", "Recipient email address"),
                            "subject" to ToolProperty("string", "Email subject"),
                            "body" to ToolProperty("string", "Email body text"),
                            "account" to ToolProperty("string", "Account email or account_id to send from (optional with a single connected account)"),
                        ),
                        required = listOf("to", "subject", "body"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "reply_email",
                    description = "Draft a reply to a received email. Looks up the original by uid to pick the sender " +
                        "and thread the subject and Message-ID, then stages a draft in the review banner — the user " +
                        "must tap Send to actually send.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "uid" to ToolProperty("integer", "Uid of the email being replied to (from check_email / search_email)"),
                            "body" to ToolProperty("string", "Reply text"),
                            "account" to ToolProperty("string", "Account email or account_id (optional with a single connected account)"),
                        ),
                        required = listOf("uid", "body"),
                    ),
                ),
            ),
        )

        private fun formatTimestamp(epochMs: Long): String {
            if (epochMs <= 0) return "(unknown date)"
            return java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }
    }
}
