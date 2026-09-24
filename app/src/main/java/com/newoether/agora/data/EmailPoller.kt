package com.newoether.agora.data

import com.newoether.agora.email.EmailFetchedMessage
import com.newoether.agora.email.ImapClient

/**
 * Reads new emails over IMAP for one poll. The indirection exists so the poller's
 * watermark/dedup logic can be tested without sockets, mirroring [SmsReader].
 */
interface EmailInboxReader {
    /** Opens and authenticates a session for [account]; returns null on login failure. */
    suspend fun open(account: EmailAccount, password: String): OpenInbox?

    suspend fun searchUnseen(client: ImapClient): List<Long>
    suspend fun fetchHeaders(
        client: ImapClient,
        uids: List<Long>,
        accountId: String,
    ): List<EmailFetchedMessage>
    suspend fun close(client: ImapClient)
}

/** An authenticated IMAP session with its INBOX selected. */
data class OpenInbox(val client: ImapClient, val messageCount: Int)

/** Default [EmailInboxReader] over real IMAP connections. */
class ImapInboxReader : EmailInboxReader {
    override suspend fun open(account: EmailAccount, password: String): OpenInbox? {
        val client = ImapClient(account.imapHost, account.imapPort, tls = true)
        client.connect()
        if (!client.login(account.email, password)) {
            client.logout()
            return null
        }
        val count = client.selectInbox()
        return OpenInbox(client, count)
    }

    override suspend fun searchUnseen(client: ImapClient): List<Long> = client.searchUnseen()

    override suspend fun fetchHeaders(
        client: ImapClient,
        uids: List<Long>,
        accountId: String,
    ): List<EmailFetchedMessage> = client.fetchHeaders(uids, accountId)

    override suspend fun close(client: ImapClient) = client.logout()
}

/**
 * Polls for new emails (per account) and saves them to the store.
 *
 * Runs inside the HeartbeatScheduler's loop. The first poll after an account is
 * connected seeds the UID watermark to the current maximum unseen UID so existing
 * history is never dumped into the pending queue. New messages are filtered by
 * the watermark and deduped against the pending queue — two polls without an
 * intervening heartbeat never double-enqueue the same message. Failure recording
 * persists the error and the attempt timestamp so interval gating backs off a
 * failing account instead of hammering it every scheduler tick.
 */
class EmailPoller(
    private val emailStore: EmailStore,
    private val accountProvider: suspend () -> List<EmailAccount>,
    private val passwordProvider: suspend (String) -> String?,
    private val inboxReader: EmailInboxReader = ImapInboxReader(),
) {
    /** Polls every configured account once; one account's failure never blocks the others. */
    suspend fun poll() = poll(accountProvider())

    /**
     * Polls the given accounts. The scheduler passes only interval-due accounts so a
     * recently synced (or backing-off) account never forces an IMAP connection.
     */
    suspend fun poll(accounts: List<EmailAccount>) {
        for (account in accounts) {
            pollAccount(account)
        }
    }

    private suspend fun pollAccount(account: EmailAccount) {
        val password = passwordProvider(account.id) ?: run {
            recordFailure(account.id, System.currentTimeMillis(), "Account credentials missing")
            return
        }
        val attemptAt = System.currentTimeMillis()
        var inbox: OpenInbox? = null
        try {
            inbox = inboxReader.open(account, password)
                ?: run {
                    recordFailure(account.id, attemptAt, "IMAP login failed")
                    return
                }
            val state = emailStore.getSyncStateOnce(account.id)

            // First poll after connect: seed the watermark with the newest existing
            // UID and enqueue nothing — everything already in the inbox is history.
            if (state.lastSeenUid == 0L) {
                val maxUid = inboxReader.searchUnseen(inbox.client).maxOrNull() ?: 0L
                emailStore.updateSyncState(
                    account.id,
                    state.copy(
                        lastSeenUid = maxUid,
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        unreadCount = 0,
                        lastError = null,
                    ),
                )
                return
            }

            val pendingUids = emailStore.getPendingSnapshot()
                .filter { it.accountId == account.id }
                .map { it.uid }
                .toSet()

            val candidates = inboxReader.searchUnseen(inbox.client)
                .filter { it > state.lastSeenUid && it !in pendingUids }
                .sorted()

            if (candidates.isEmpty()) {
                emailStore.updateSyncState(
                    account.id,
                    state.copy(
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        lastError = null,
                    ),
                )
                return
            }

            val limited = candidates.take(MAX_FETCH_PER_POLL)
            val fetched = inboxReader.fetchHeaders(inbox.client, limited, account.id)
            if (fetched.isNotEmpty()) {
                val data = fetched.map { it.toMessageData(account.id) }
                emailStore.saveMessages(
                    accountId = account.id,
                    messages = data,
                    updatedState = state.copy(
                        lastSeenUid = limited.max(),
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        unreadCount = fetched.count { !it.isRead },
                        lastError = null,
                    ),
                )
            } else {
                // The server reported candidates but none survived the fetch; still
                // advance the watermark so the next poll doesn't retry them forever.
                emailStore.updateSyncState(
                    account.id,
                    state.copy(
                        lastSeenUid = limited.max(),
                        lastSyncEpochMs = attemptAt,
                        lastAttemptEpochMs = attemptAt,
                        lastError = null,
                    ),
                )
            }
        } catch (e: Exception) {
            // Exception messages originate from ImapClient's server-response quotes
            // only — never from the sent command — so credentials can't leak into Room.
            recordFailure(account.id, attemptAt, e.message ?: e::class.simpleName ?: "Poll failed")
        } finally {
            inbox?.let { runCatching { inboxReader.close(it.client) } }
        }
    }

    private suspend fun recordFailure(accountId: String, attemptAt: Long, error: String) {
        val state = emailStore.getSyncStateOnce(accountId)
        emailStore.updateSyncState(
            accountId,
            state.copy(
                lastAttemptEpochMs = attemptAt,
                lastError = error,
            ),
        )
    }

    private fun EmailFetchedMessage.toMessageData(accountId: String) = EmailMessageData(
        accountId = accountId,
        uid = uid,
        fromAddress = fromAddress,
        toAddress = toAddress,
        subject = subject,
        dateEpochMs = dateEpochMs,
        preview = preview,
        body = body,
        messageId = messageId,
        isRead = isRead,
        listUnsubscribe = listUnsubscribe,
        listUnsubscribePost = listUnsubscribePost,
    )

    companion object {
        const val MAX_FETCH_PER_POLL = 50
    }
}
