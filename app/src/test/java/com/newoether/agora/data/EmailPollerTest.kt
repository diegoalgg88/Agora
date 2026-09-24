package com.newoether.agora.data

import com.newoether.agora.email.EmailFetchedMessage
import com.newoether.agora.email.ImapClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EmailPollerTest {

    private val account = EmailAccount(
        id = "acc-1",
        email = "user@fastmail.com",
        imapHost = "imap.fastmail.com",
        smtpHost = "smtp.fastmail.com",
    )

    private fun fetched(uid: Long, read: Boolean = false) = EmailFetchedMessage(
        uid = uid,
        accountId = account.id,
        fromAddress = "a@b.com",
        toAddress = "user@fastmail.com",
        subject = "Subject $uid",
        dateRaw = "Mon, 21 Sep 2026 10:00:00 +0000",
        dateEpochMs = 1000L,
        preview = "Preview $uid",
        body = "Body $uid",
        bodyHtml = "",
        messageId = "<m$uid@b.com>",
        isRead = read,
        listUnsubscribe = "",
        listUnsubscribePost = "",
    )

    private fun reader(
        unseen: List<Long>,
        headers: Map<Long, EmailFetchedMessage> = emptyMap(),
        loginFails: Boolean = false,
    ): EmailInboxReader {
        val client = mockk<ImapClient>(relaxed = true)
        return object : EmailInboxReader {
            override suspend fun open(account: EmailAccount, password: String): OpenInbox? {
                if (loginFails) return null
                return OpenInbox(client, unseen.size)
            }

            override suspend fun searchUnseen(client: ImapClient): List<Long> = unseen

            override suspend fun fetchHeaders(
                client: ImapClient,
                uids: List<Long>,
                accountId: String,
            ): List<EmailFetchedMessage> = uids.mapNotNull { headers[it] }

            override suspend fun close(client: ImapClient) {}
        }
    }

    private fun store(state: EmailSyncState, pending: List<EmailPendingData> = emptyList()): EmailStore =
        mockk<EmailStore>(relaxed = true).apply {
            coEvery { getSyncStateOnce(any()) } returns state
            coEvery { getPendingSnapshot() } returns pending
        }

    private fun poller(store: EmailStore, inbox: EmailInboxReader) = EmailPoller(
        emailStore = store,
        accountProvider = { listOf(account) },
        passwordProvider = { "app-password" },
        inboxReader = inbox,
    )

    @Test
    fun `first poll seeds watermark and enqueues nothing`() = runBlocking {
        val store = store(EmailSyncState()) // lastSeenUid = 0
        val inbox = reader(unseen = listOf(10L, 20L, 30L))

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(
                account.id,
                match { state -> state.lastSeenUid == 30L && state.lastError == null },
            )
        }
        coVerify(exactly = 0) { store.saveMessages(any(), any(), any()) }
    }

    @Test
    fun `new messages beyond watermark are saved and watermark advances to batch max`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val inbox = reader(
            unseen = listOf(30L, 31L, 32L, 33L),
            headers = mapOf(31L to fetched(31L), 32L to fetched(32L, read = true), 33L to fetched(33L)),
        )

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.saveMessages(
                accountId = account.id,
                messages = match { messages -> messages.map { it.uid } == listOf(31L, 32L, 33L) },
                updatedState = match { state ->
                    state.lastSeenUid == 33L && state.unreadCount == 2 && state.lastError == null
                },
            )
        }
    }

    @Test
    fun `uids already in the pending queue are not re-enqueued`() = runBlocking {
        val store = store(
            EmailSyncState(lastSeenUid = 30L),
            pending = listOf(
                EmailPendingData(account.id, 31L, "a@b.com", "s", 0L, "p"),
            ),
        )
        val inbox = reader(
            unseen = listOf(31L, 32L),
            headers = mapOf(31L to fetched(31L), 32L to fetched(32L)),
        )

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.saveMessages(
                accountId = account.id,
                messages = match { messages -> messages.map { it.uid } == listOf(32L) },
                updatedState = match { state -> state.lastSeenUid == 32L },
            )
        }
    }

    @Test
    fun `same-uid collision across accounts is respected in dedupe`() = runBlocking {
        val otherAccountPending = listOf(
            EmailPendingData("acc-2", 31L, "x@y.com", "s", 0L, "p"),
        )
        val store = store(EmailSyncState(lastSeenUid = 30L), pending = otherAccountPending)
        val inbox = reader(
            unseen = listOf(31L),
            headers = mapOf(31L to fetched(31L)),
        )

        poller(store, inbox).poll()

        // 31 belongs to another account's queue; this account may enqueue its own 31.
        coVerify(exactly = 1) {
            store.saveMessages(any(), match { messages -> messages.map { it.uid } == listOf(31L) }, any())
        }
    }

    @Test
    fun `no new messages only refreshes sync timestamps`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val inbox = reader(unseen = listOf(5L, 30L))

        poller(store, inbox).poll()

        coVerify(exactly = 0) { store.saveMessages(any(), any(), any()) }
        coVerify(exactly = 1) {
            store.updateSyncState(account.id, match { it.lastError == null && it.lastSeenUid == 30L })
        }
    }

    @Test
    fun `login failure records error without touching the watermark`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val inbox = reader(unseen = emptyList(), loginFails = true)

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(
                account.id,
                match { it.lastError == "IMAP login failed" && it.lastSeenUid == 30L },
            )
        }
        coVerify(exactly = 0) { store.saveMessages(any(), any(), any()) }
    }

    @Test
    fun `reader exception records failure and never saves partial data`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val inbox = reader(unseen = listOf(31L)).let { reader ->
            object : EmailInboxReader by reader {
                override suspend fun fetchHeaders(
                    client: ImapClient,
                    uids: List<Long>,
                    accountId: String,
                ): List<EmailFetchedMessage> = throw Exception("Connection closed")
            }
        }

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(
                account.id,
                match { it.lastError == "Connection closed" && it.lastSeenUid == 30L },
            )
        }
        coVerify(exactly = 0) { store.saveMessages(any(), any(), any()) }
    }

    @Test
    fun `failed fetch still advances watermark to avoid refetch loops`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val inbox = reader(unseen = listOf(31L, 32L), headers = emptyMap())

        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.updateSyncState(
                account.id,
                match { it.lastSeenUid == 32L && it.lastError == null },
            )
        }
        coVerify(exactly = 0) { store.saveMessages(any(), any(), any()) }
    }

    @Test
    fun `missing password records failure for that account only`() = runBlocking {
        val store = store(EmailSyncState(lastSeenUid = 30L))
        val poller = EmailPoller(
            emailStore = store,
            accountProvider = { listOf(account) },
            passwordProvider = { null },
            inboxReader = reader(unseen = emptyList()),
        )

        poller.poll()

        coVerify(exactly = 1) {
            store.updateSyncState(
                account.id,
                match { it.lastError == "Account credentials missing" },
            )
        }
    }

    @Test
    fun `poll caps the batch at MAX_FETCH_PER_POLL`() = runBlocking {
        val uids = (1L..120L).toList()
        val headers = uids.associateWith { fetched(it) }
        val inbox = reader(unseen = uids, headers = headers)
        val store = store(EmailSyncState(lastSeenUid = 0L))

        poller(store, inbox).poll() // seed

        // Second poll: watermark at 50 after a capped first fetch would refetch the rest;
        // simulate the post-seed state directly to exercise the capped fetch path.
        coEvery { store.getSyncStateOnce(any()) } returns EmailSyncState(lastSeenUid = 1L)
        poller(store, inbox).poll()

        coVerify(exactly = 1) {
            store.saveMessages(
                accountId = account.id,
                messages = match { messages -> messages.size == EmailPoller.MAX_FETCH_PER_POLL },
                updatedState = match { it.lastSeenUid == 51L },
            )
        }
        assertEquals(50, EmailPoller.MAX_FETCH_PER_POLL)
    }
}
