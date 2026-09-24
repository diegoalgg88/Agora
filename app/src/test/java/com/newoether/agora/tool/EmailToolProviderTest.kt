package com.newoether.agora.tool

import com.newoether.agora.data.EmailAccount
import com.newoether.agora.data.EmailDraftStore
import com.newoether.agora.data.EmailPendingData
import com.newoether.agora.data.EmailStore
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.EmailMessageEntity
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.email.ImapClient
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailToolProviderTest {

    private val account = EmailAccount(
        id = "acc-1",
        email = "user@fastmail.com",
        imapHost = "imap.fastmail.com",
        smtpHost = "smtp.fastmail.com",
    )

    private fun provider(
        accounts: List<EmailAccount>,
        emailStore: EmailStore = mockk(relaxed = true),
        imapFactory: (EmailAccount) -> ImapClient = { mockk(relaxed = true) },
        settingsManager: SettingsManager = mockk<SettingsManager>(relaxed = true).apply {
            coEvery { emailAccountSettings.accountsOnce() } returns accounts
            coEvery { emailAccountSettings.passwordFor(any()) } returns "app-password"
        },
    ): EmailToolProvider {
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.emailAccounts } returns MutableStateFlow(accounts)
        return EmailToolProvider(
            emailStore = emailStore,
            emailDraftStore = mockk<EmailDraftStore>(relaxed = true),
            settingsRepository = settingsRepository,
            settingsManager = settingsManager,
            imapClientFactory = imapFactory,
        )
    }

    @Test
    fun `setup_email stays visible with zero accounts and read tools hidden`() {
        val provider = provider(accounts = emptyList())
        val definitions = provider.definitions(mockGenerationContext())
        val names = definitions.map { it.function.name }

        assertTrue(names.contains("setup_email"))
        assertFalse(names.contains("check_email"))
        assertFalse(names.contains("read_email"))
        assertFalse(names.contains("search_email"))
    }

    @Test
    fun `read tools visible once one account is connected`() {
        val provider = provider(accounts = listOf(account))
        val names = provider.definitions(mockGenerationContext()).map { it.function.name }

        assertTrue(names.contains("setup_email"))
        assertTrue(names.contains("check_email"))
        assertTrue(names.contains("read_email"))
        assertTrue(names.contains("search_email"))
    }

    @Test
    fun `check_email lists pending snapshot newest-last-20`() = runBlocking {
        val pending = (1L..25L).map { uid ->
            EmailPendingData(account.id, uid, "sender$uid@x.com", "Subject $uid", uid * 1000L, "Preview $uid")
        }
        val emailStore = mockk<EmailStore>()
        coEvery { emailStore.getPendingSnapshot() } returns pending
        val provider = provider(accounts = listOf(account), emailStore = emailStore)

        val result = provider.execute("check_email", "{}", mockGenerationContext())

        assertTrue(result.contains("25 new email"))
        assertTrue(result.contains("UID: 25"))
        assertFalse(result.contains("UID: 1\n"))
    }

    @Test
    fun `resolveAccount falls back to the single account without argument`() = runBlocking {
        val emailStore = mockk<EmailStore>()
        val message = EmailMessageEntity(
            accountId = account.id,
            uid = 42L,
            fromAddress = "a@b.com",
            toAddress = "user@fastmail.com",
            subject = "Hello",
            dateEpochMs = 1000L,
            preview = "p",
            body = "Full body",
        )
        coEvery { emailStore.getMessageByUid(account.id, 42L) } returns message
        val provider = provider(accounts = listOf(account), emailStore = emailStore)

        val result = provider.execute("read_email", """{"uid": 42}""", mockGenerationContext())

        assertTrue(result.contains("Full body"))
        assertTrue(result.contains("Subject: Hello"))
        assertTrue(result.contains("user@fastmail.com"))
    }

    @Test
    fun `resolveAccount accepts the account email`() = runBlocking {
        val emailStore = mockk<EmailStore>()
        coEvery { emailStore.getMessageByUid(account.id, 7L) } returns null
        val imap = mockk<ImapClient>(relaxed = true)
        val provider = provider(
            accounts = listOf(account),
            emailStore = emailStore,
            imapFactory = { imap },
        )

        val result = provider.execute("read_email", """{"uid": 7, "account": "USER@FASTMAIL.COM"}""", mockGenerationContext())

        // Cached is null and the IMAP fallback fetch also returns nothing.
        assertTrue(result.contains("No email found with uid 7"))
    }

    @Test
    fun `multiple accounts without argument return a correction hint`() = runBlocking {
        val second = account.copy(id = "acc-2", email = "other@gmail.com")
        val provider = provider(accounts = listOf(account, second))

        val result = provider.execute("check_email", "{}", mockGenerationContext())

        // check_email works across accounts, but read_email's hint is what matters.
        val readResult = provider.execute("read_email", """{"uid": 1}""", mockGenerationContext())
        assertTrue(readResult.contains("Multiple accounts are connected"))
        assertTrue(readResult.contains(account.id))
        assertTrue(readResult.contains(second.id))
    }

    @Test
    fun `read_email without any account asks for setup_email`() = runBlocking {
        val provider = provider(accounts = emptyList())

        val result = provider.execute("read_email", """{"uid": 1}""", mockGenerationContext())

        assertTrue(result.contains("No email account is connected"))
        assertTrue(result.contains("setup_email"))
    }

    @Test
    fun `search_email requires at least one criterion`() = runBlocking {
        val provider = provider(accounts = listOf(account))

        val result = provider.execute("search_email", "{}", mockGenerationContext())

        assertTrue(result.contains("Provide at least one"))
    }

    @Test
    fun `setup_email never echoes the password on success`() = runBlocking {
        val imap = mockk<ImapClient>(relaxed = true)
        coEvery { imap.login(any(), any()) } returns true
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery { settingsManager.emailAccountSettings.addAccount(any(), any()) } returns Unit
        val provider = provider(accounts = emptyList(), imapFactory = { imap }, settingsManager = settingsManager)

        val result = provider.execute(
            "setup_email",
            """{"email": "user@fastmail.com", "password": "SUPER-SECRET-PW"}""",
            mockGenerationContext(),
        )

        assertTrue(result.contains("connected"))
        assertFalse("password must not appear in the tool result", result.contains("SUPER-SECRET-PW"))
    }

    @Test
    fun `setup_email never echoes the password on failed login`() = runBlocking {
        val imap = mockk<ImapClient>(relaxed = true)
        coEvery { imap.login(any(), any()) } returns false
        val provider = provider(accounts = emptyList(), imapFactory = { imap })

        val result = provider.execute(
            "setup_email",
            """{"email": "user@fastmail.com", "password": "SUPER-SECRET-PW"}""",
            mockGenerationContext(),
        )

        assertTrue(result.contains("IMAP login failed"))
        assertFalse("password must not appear in the failure result", result.contains("SUPER-SECRET-PW"))
    }

    @Test
    fun `setup_email never echoes the password on connect exception`() = runBlocking {
        val imap = mockk<ImapClient>()
        coEvery { imap.connect() } throws Exception("Connection closed")
        val provider = provider(accounts = emptyList(), imapFactory = { imap })

        val result = provider.execute(
            "setup_email",
            """{"email": "user@fastmail.com", "password": "SUPER-SECRET-PW"}""",
            mockGenerationContext(),
        )

        assertTrue(result.contains("Could not reach the IMAP server"))
        assertFalse("password must not appear in the error path", result.contains("SUPER-SECRET-PW"))
    }

    @Test
    fun `setup_email rejects missing password without probing`() = runBlocking {
        val provider = provider(accounts = emptyList())

        val result = provider.execute("setup_email", """{"email": "user@fastmail.com"}""", mockGenerationContext())

        assertTrue(result.contains("Missing required argument: password"))
    }

    @Test
    fun `send_email stages a draft and never touches smtp`() = runBlocking {
        val emailStore = mockk<EmailStore>()
        val draftStore = mockk<EmailDraftStore>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.emailAccounts } returns MutableStateFlow(listOf(account))
        val provider = EmailToolProvider(
            emailStore = emailStore,
            emailDraftStore = draftStore,
            settingsRepository = settingsRepository,
            settingsManager = mockk<SettingsManager>(relaxed = true).apply {
                coEvery { emailAccountSettings.accountsOnce() } returns listOf(account)
            },
            imapClientFactory = { mockk<ImapClient>(relaxed = true) },
        )

        val result = provider.execute(
            "send_email",
            """{"to": "dest@example.com", "subject": "Hello", "body": "World"}""",
            mockGenerationContext(),
        )

        assertTrue(result.contains("draft created"))
        assertTrue(result.contains("User must confirm to send"))
        // Staging only: exactly one draft, and the IMAP/SMTP factory is never invoked.
        io.mockk.coVerify(exactly = 1) {
            draftStore.addDraft(match { draft ->
                draft.accountId == account.id &&
                    draft.toAddress == "dest@example.com" &&
                    draft.subject == "Hello" &&
                    draft.inReplyToMessageId == null
            })
        }
    }

    @Test
    fun `reply_email threads subject and message-id from the original`() = runBlocking {
        val emailStore = mockk<EmailStore>()
        val original = com.newoether.agora.data.local.EmailMessageEntity(
            accountId = account.id,
            uid = 42L,
            fromAddress = "sender@x.com",
            toAddress = account.email,
            subject = "Quarterly report",
            dateEpochMs = 1000L,
            preview = "p",
            body = "Full body",
            messageId = "<orig@x.com>",
        )
        coEvery { emailStore.getMessageByUid(account.id, 42L) } returns original
        val draftStore = mockk<EmailDraftStore>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.emailAccounts } returns MutableStateFlow(listOf(account))
        val provider = EmailToolProvider(
            emailStore = emailStore,
            emailDraftStore = draftStore,
            settingsRepository = settingsRepository,
            settingsManager = mockk<SettingsManager>(relaxed = true).apply {
                coEvery { emailAccountSettings.accountsOnce() } returns listOf(account)
            },
            imapClientFactory = { mockk<ImapClient>(relaxed = true) },
        )

        val result = provider.execute(
            "reply_email",
            """{"uid": 42, "body": "Thanks!"}""",
            mockGenerationContext(),
        )

        assertTrue(result.contains("Reply draft created"))
        io.mockk.coVerify(exactly = 1) {
            draftStore.addDraft(match { draft ->
                draft.toAddress == "sender@x.com" &&
                    draft.subject == "Re: Quarterly report" &&
                    draft.inReplyToMessageId == "<orig@x.com>" &&
                    draft.body == "Thanks!"
            })
        }
    }

    @Test
    fun `reply_email keeps an existing Re prefix`() = runBlocking {
        val emailStore = mockk<EmailStore>()
        val original = com.newoether.agora.data.local.EmailMessageEntity(
            accountId = account.id,
            uid = 7L,
            fromAddress = "s@x.com",
            toAddress = account.email,
            subject = "Re: Already a reply",
            dateEpochMs = 0L,
            preview = "",
            body = "",
        )
        coEvery { emailStore.getMessageByUid(account.id, 7L) } returns original
        val draftStore = mockk<EmailDraftStore>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.emailAccounts } returns MutableStateFlow(listOf(account))
        val provider = EmailToolProvider(
            emailStore = emailStore,
            emailDraftStore = draftStore,
            settingsRepository = settingsRepository,
            settingsManager = mockk<SettingsManager>(relaxed = true).apply {
                coEvery { emailAccountSettings.accountsOnce() } returns listOf(account)
            },
            imapClientFactory = { mockk<ImapClient>(relaxed = true) },
        )

        provider.execute("reply_email", """{"uid": 7, "body": "ok"}""", mockGenerationContext())

        io.mockk.coVerify(exactly = 1) {
            draftStore.addDraft(match { it.subject == "Re: Already a reply" })
        }
    }

    private fun mockGenerationContext(): GenerationContext = mockk(relaxed = true)
}
