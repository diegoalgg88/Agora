package com.newoether.agora.data

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.local.EmailDraftEntity
import com.newoether.agora.data.local.EmailDraftStatus
import com.newoether.agora.email.ImapClient
import com.newoether.agora.email.SmtpClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailDraftStoreTest {

    private val account = EmailAccount(
        id = "acc-1",
        email = "user@fastmail.com",
        imapHost = "imap.fastmail.com",
        smtpHost = "smtp.fastmail.com",
    )

    private fun draft(id: String = "d1", status: EmailDraftStatus = EmailDraftStatus.PENDING) = EmailDraft(
        id = id,
        accountId = account.id,
        toAddress = "dest@example.com",
        subject = "Hi",
        body = "Hello",
        status = status,
    )

    private fun entity(draft: EmailDraft) = EmailDraftEntity(
        id = draft.id,
        accountId = draft.accountId,
        toAddress = draft.toAddress,
        subject = draft.subject,
        body = draft.body,
        createdAtEpochMs = draft.createdAtEpochMs,
        inReplyToMessageId = draft.inReplyToMessageId,
        status = draft.status,
        lastError = draft.lastError,
    )

    /** DAO whose atomic claim succeeds (returns 1) unless [claimResult] says another caller won. */
    private fun daoWith(vararg initial: EmailDraft, claimResult: Int = 1): ChatDao {
        val chatDao = mockk<ChatDao>(relaxed = true)
        every { chatDao.getEmailDraftsFlow() } returns MutableStateFlow(initial.map(::entity))
        coEvery { chatDao.claimEmailDraftForSending(any()) } returns claimResult
        return chatDao
    }

    private fun storeWith(
        vararg initial: EmailDraft,
        smtp: SmtpClient = mockk(relaxed = true),
        imap: ImapClient = mockk(relaxed = true),
        chatDao: ChatDao = daoWith(*initial),
    ): EmailDraftStore = spyk(
        EmailDraftStore(
            chatDao = chatDao,
            database = mockk<ChatDatabase>(relaxed = true),
            accountResolver = { id -> if (id == account.id) account else null },
            passwordResolver = { id -> if (id == account.id) "app-password" else null },
            smtpClientFactory = { smtp },
            imapClientFactory = { imap },
        ),
    )

    private fun happySmtp(): SmtpClient {
        val smtp = mockk<SmtpClient>()
        coEvery { smtp.connect() } returns Unit
        coEvery { smtp.ehlo(any()) } returns Unit
        coEvery { smtp.startTls() } returns Unit
        coEvery { smtp.authenticate(any(), any()) } returns Unit
        coEvery { smtp.sendReply(any(), any(), any(), any(), any()) } returns "raw message"
        coEvery { smtp.quit() } returns Unit
        return smtp
    }

    @Test
    fun `sendDraft claims PENDING then records SENT on success`() = runBlocking {
        val smtp = happySmtp()
        val imap = mockk<ImapClient>()
        coEvery { imap.connect() } returns Unit
        coEvery { imap.login(any(), any()) } returns true
        coEvery { imap.findSentMailbox() } returns "Sent"
        coEvery { imap.appendToMailbox(any(), any()) } returns true
        coEvery { imap.logout() } returns Unit
        val chatDao = daoWith(draft("d1"))

        val store = storeWith(draft("d1"), smtp = smtp, imap = imap, chatDao = chatDao)
        val result = store.sendDraft("d1")

        assertTrue(result)
        coVerify(exactly = 1) { chatDao.claimEmailDraftForSending("d1") }
        coVerify(exactly = 1) { store.updateStatus("d1", EmailDraftStatus.SENT, null) }
        coVerify(exactly = 1) { smtp.authenticate(account.email, "app-password") }
        // Sent copy filed into the SPECIAL-USE folder.
        coVerify(exactly = 1) { imap.appendToMailbox("Sent", "raw message") }
    }

    @Test
    fun `sendDraft records failure reason from smtp exception`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        coEvery { smtp.connect() } returns Unit
        coEvery { smtp.ehlo(any()) } returns Unit
        coEvery { smtp.startTls() } returns Unit
        coEvery { smtp.authenticate(any(), any()) } throws Exception("Authentication failed: 535 invalid")
        coEvery { smtp.quit() } returns Unit
        val chatDao = daoWith(draft("d1"))

        val store = storeWith(draft("d1"), smtp = smtp, chatDao = chatDao)
        val result = store.sendDraft("d1")

        assertFalse(result)
        coVerify(exactly = 1) { chatDao.claimEmailDraftForSending("d1") }
        coVerify(exactly = 1) {
            store.updateStatus("d1", EmailDraftStatus.FAILED, "Authentication failed: 535 invalid")
        }
    }

    @Test
    fun `retry of a FAILED draft is accepted and dispatched`() = runBlocking {
        val smtp = happySmtp()
        val imap = mockk<ImapClient>(relaxed = true)
        val chatDao = daoWith(draft("d1", EmailDraftStatus.FAILED))

        val store = storeWith(draft("d1", EmailDraftStatus.FAILED), smtp = smtp, imap = imap, chatDao = chatDao)

        assertTrue(store.sendDraft("d1"))
        coVerify(exactly = 1) { chatDao.claimEmailDraftForSending("d1") }
        coVerify(exactly = 1) { smtp.sendReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `losing the atomic claim never reaches smtp`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        // Another tap already moved the row to SENDING between the read and the claim.
        val chatDao = daoWith(draft("d1"), claimResult = 0)
        val store = storeWith(draft("d1"), smtp = smtp, chatDao = chatDao)

        assertFalse(store.sendDraft("d1"))

        coVerify(exactly = 1) { chatDao.claimEmailDraftForSending("d1") }
        coVerify(exactly = 0) { smtp.connect() }
        coVerify(exactly = 0) { store.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `failed sent-copy never turns a delivered email into FAILED`() = runBlocking {
        val smtp = happySmtp()
        val imap = mockk<ImapClient>()
        coEvery { imap.connect() } throws Exception("IMAP unreachable")
        val store = storeWith(draft("d1"), smtp = smtp, imap = imap)

        // SMTP accepted the message; the Sent-copy failure must not enable a duplicate retry.
        assertTrue(store.sendDraft("d1"))
        coVerify(exactly = 1) { store.updateStatus("d1", EmailDraftStatus.SENT, null) }
        coVerify(exactly = 0) { store.updateStatus("d1", EmailDraftStatus.FAILED, any()) }
    }

    @Test
    fun `sendDraft refuses SENDING drafts`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        val chatDao = daoWith(draft("d1", EmailDraftStatus.SENDING))
        val store = storeWith(draft("d1", EmailDraftStatus.SENDING), smtp = smtp, chatDao = chatDao)

        val result = store.sendDraft("d1")

        assertFalse(result)
        coVerify(exactly = 0) { chatDao.claimEmailDraftForSending(any()) }
        coVerify(exactly = 0) { smtp.connect() }
        coVerify(exactly = 0) { store.updateStatus(any(), any(), any()) }
    }

    @Test
    fun `sendDraft refuses already SENT drafts`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        val store = storeWith(draft("d1", EmailDraftStatus.SENT), smtp = smtp)

        assertFalse(store.sendDraft("d1"))
        coVerify(exactly = 0) { smtp.connect() }
    }

    @Test
    fun `sendDraft returns false for unknown draft`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        val store = storeWith(draft("d1"), smtp = smtp)

        val result = store.sendDraft("missing")

        assertFalse(result)
        coVerify(exactly = 0) { smtp.connect() }
    }

    @Test
    fun `sendDraft fails closed when the account is gone`() = runBlocking {
        val smtp = mockk<SmtpClient>()
        val chatDao = mockk<ChatDao>(relaxed = true)
        every { chatDao.getEmailDraftsFlow() } returns MutableStateFlow(
            listOf(
                EmailDraftEntity(
                    id = "d1",
                    accountId = "gone",
                    toAddress = "x@y.com",
                    subject = "s",
                    body = "b",
                    createdAtEpochMs = 0L,
                ),
            ),
        )
        val store = spyk(
            EmailDraftStore(
                chatDao = chatDao,
                database = mockk<ChatDatabase>(relaxed = true),
                accountResolver = { null },
                passwordResolver = { null },
                smtpClientFactory = { smtp },
            ),
        )

        val result = store.sendDraft("d1")

        assertFalse(result)
        coVerify(exactly = 1) {
            store.updateStatus("d1", EmailDraftStatus.FAILED, "Account no longer connected")
        }
        coVerify(exactly = 0) { chatDao.claimEmailDraftForSending(any()) }
        coVerify(exactly = 0) { smtp.connect() }
    }

    @Test
    fun `gmail accounts skip the IMAP sent copy entirely`() = runBlocking {
        val gmailAccount = account.copy(id = "g1", email = "me@gmail.com")
        val smtp = happySmtp()
        val imap = mockk<ImapClient>()

        val chatDao = mockk<ChatDao>(relaxed = true)
        every { chatDao.getEmailDraftsFlow() } returns MutableStateFlow(
            listOf(
                EmailDraftEntity(
                    id = "d1",
                    accountId = "g1",
                    toAddress = "x@y.com",
                    subject = "s",
                    body = "b",
                    createdAtEpochMs = 0L,
                ),
            ),
        )
        coEvery { chatDao.claimEmailDraftForSending(any()) } returns 1
        val store = spyk(
            EmailDraftStore(
                chatDao = chatDao,
                database = mockk<ChatDatabase>(relaxed = true),
                accountResolver = { gmailAccount },
                passwordResolver = { "pw" },
                smtpClientFactory = { smtp },
                imapClientFactory = { imap },
            ),
        )

        assertTrue(store.sendDraft("d1"))
        // Gmail's SMTP already files outgoing mail into Sent — no IMAP connection at all.
        coVerify(exactly = 0) { imap.connect() }
        coVerify(exactly = 1) { store.updateStatus("d1", EmailDraftStatus.SENT, null) }
    }

    @Test
    fun `interrupted SENDING drafts are failed once per process`() = runBlocking {
        val chatDao = daoWith()
        val store = storeWith(chatDao = chatDao)

        store.recoverInterruptedDrafts()
        store.recoverInterruptedDrafts()

        coVerify(exactly = 1) { chatDao.failInterruptedEmailDrafts(any()) }
    }
}
