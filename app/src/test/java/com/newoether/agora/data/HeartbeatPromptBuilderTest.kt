package com.newoether.agora.data

import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.data.repository.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPromptBuilderTest {

    private fun builder(): HeartbeatPromptBuilder {
        val taskManager = mockk<TaskManager>(relaxed = true)
        every { taskManager.tasks } returns MutableStateFlow(emptyList<TaskEntity>())
        return HeartbeatPromptBuilder(
            taskManager = taskManager,
            loopManager = mockk(relaxed = true),
            conversationRepository = mockk<ConversationRepository>(relaxed = true),
            memoryManager = mockk(relaxed = true),
            taskRepository = mockk(relaxed = true),
        )
    }

    private fun sms(id: Long, address: String, preview: String) = SmsMessageData(
        id = id,
        address = address,
        date = 1_700_000_000_000L,
        preview = preview,
        body = "",
        read = false,
    )

    @Test
    fun `omits New SMS section when no pending messages`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## New SMS" in prompt)
    }

    @Test
    fun `includes New SMS with sender and preview`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingSms = listOf(sms(42L, "+15551234567", "Hello from Agora")),
        )
        assertTrue("## New SMS" in prompt)
        assertTrue("+15551234567" in prompt)
        assertTrue("id: 42" in prompt)
        assertTrue("Hello from Agora" in prompt)
    }

    @Test
    fun `New SMS renders placeholder for blank sender and omits empty preview`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingSms = listOf(sms(7L, "", "")),
        )
        assertTrue("(unknown sender)" in prompt)
        // Scope to the SMS line itself: the trailing ## Response Rule section legitimately
        // contains "HEARTBEAT_OK: reply" and would trip a whole-prompt assertion. Only what
        // comes AFTER the id marker can be the preview: "(id: 7)" itself contains ": ".
        val smsLine = prompt.lines().first { "(id: 7)" in it }
        assertFalse("unexpected preview colon in [$smsLine]", ": " in smsLine.substringAfter("(id: 7)"))
    }

    @Test
    fun `includes New Notifications section`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingNotifications = listOf(
                NotificationRecord(
                    id = "n1",
                    packageName = "com.whatsapp",
                    appLabel = "WhatsApp",
                    title = "T",
                    text = "B",
                    postedAt = 1_700_000_000_000L,
                    preview = "New message",
                ),
            ),
        )
        assertTrue("## New Notifications" in prompt)
        assertTrue("WhatsApp" in prompt)
        assertTrue("New message" in prompt)
    }

    @Test
    fun `omits both sections when empty and keeps custom prompt`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "Always be concise.",
            pendingSms = emptyList(),
            pendingNotifications = emptyList(),
        )
        assertFalse("## New SMS" in prompt)
        assertFalse("## New Notifications" in prompt)
        assertFalse("## New Emails" in prompt)
        assertTrue("## Custom Instructions" in prompt)
        assertTrue("Always be concise." in prompt)
    }

    @Test
    fun `adds the Response Rule last when incoming items are present`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingSms = listOf(sms(1L, "TELCEL", "La fecha limite de pago ya vencio")),
            recentResponses = listOf("HEARTBEAT_OK"),
        )
        assertTrue("## Response Rule" in prompt)
        assertTrue(prompt.indexOf("## Response Rule") > prompt.indexOf("## Previous Heartbeat Results"))
        assertTrue("do NOT answer HEARTBEAT_OK" in prompt)
    }

    @Test
    fun `omits the Response Rule when nothing incoming`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## Response Rule" in prompt)
    }

    @Test
    fun `never emits an empty Pending Tasks and Loops header`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## Pending Tasks & Loops" in prompt)
    }

    private fun email(uid: Long, from: String, subject: String, preview: String, date: Long = 1L) =
        EmailPendingData(
            accountId = "acc-1",
            uid = uid,
            fromAddress = from,
            subject = subject,
            dateEpochMs = date,
            preview = preview,
        )

    @Test
    fun `omits New Emails section when no pending emails`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## New Emails" in prompt)
    }

    @Test
    fun `includes New Emails with sender subject uid and preview`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingEmails = listOf(email(42L, "boss@corp.com", "Meeting moved", "See you at 4")),
        )
        assertTrue("## New Emails" in prompt)
        assertTrue("boss@corp.com" in prompt)
        assertTrue("Meeting moved" in prompt)
        assertTrue("uid: 42" in prompt)
        assertTrue("account: acc-1" in prompt)
        assertTrue("See you at 4" in prompt)
    }

    @Test
    fun `New Emails caps at 20 and sorts newest first`() = runBlocking {
        val emails = (1L..25L).map { uid ->
            email(uid, "s$uid@x.com", "Subject $uid", "p$uid", date = uid)
        }
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "", pendingEmails = emails)

        assertTrue("uid: 25" in prompt)
        assertFalse("uid: 5" in prompt)
        val uid25 = prompt.indexOf("uid: 25")
        val uid24 = prompt.indexOf("uid: 24")
        assertTrue(uid25 < uid24)
    }

    @Test
    fun `New Emails renders placeholders for blank sender and subject`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            pendingEmails = listOf(email(7L, "", "", "")),
        )
        assertTrue("(unknown sender)" in prompt)
        assertTrue("(no subject)" in prompt)
    }

    @Test
    fun `New SMS caps at the shared shown limit`() = runBlocking {
        val messages = (1L..25L).map { id -> sms(id, "+15$id", "p$id") }
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "", pendingSms = messages)
        // Newest first per the builder's render order: the test fixtures share one date,
        // so ordering is stable — the first HeartbeatShownLimits.SMS ids appear, the rest not.
        assertTrue("id: 20" in prompt)
        assertFalse("id: 21" in prompt)
    }

    @Test
    fun `New Notifications caps at the shared shown limit`() = runBlocking {
        val records = (1..25).map { n ->
            NotificationRecord(
                id = "n$n",
                packageName = "com.app",
                appLabel = "App $n",
                title = "T$n",
                text = "B",
                postedAt = n.toLong(),
                preview = "p$n",
            )
        }
        val prompt = builder().buildHeartbeatPrompt(customPrompt = "", pendingNotifications = records)
        assertTrue("n25" in prompt)
        assertFalse("n5" in prompt)
    }

    @Test
    fun `includes Email Account Status with unread count and last sync`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            emailStatuses = listOf(
                EmailAccountStatus(email = "boss@corp.com", unreadCount = 3, lastSyncEpochMs = 1_700_000_000_000L),
            ),
        )
        assertTrue("## Email Account Status" in prompt)
        assertTrue("boss@corp.com" in prompt)
        assertTrue("3 unread" in prompt)
        assertTrue("last sync" in prompt)
    }

    @Test
    fun `omits Email Account Status when no accounts and renders never synced for zero`() = runBlocking {
        val omitted = builder().buildHeartbeatPrompt(customPrompt = "")
        assertFalse("## Email Account Status" in omitted)

        val neverSynced = builder().buildHeartbeatPrompt(
            customPrompt = "",
            emailStatuses = listOf(EmailAccountStatus(email = "a@b.c", unreadCount = 0, lastSyncEpochMs = 0L)),
        )
        assertTrue("(never synced)" in neverSynced)
    }

    @Test
    fun `Previous Heartbeat Results explains its trend-detection purpose`() = runBlocking {
        val prompt = builder().buildHeartbeatPrompt(
            customPrompt = "",
            recentResponses = listOf("Nothing new"),
        )
        assertTrue("track trends" in prompt)
        assertTrue("avoid repeating" in prompt)
    }

    @Test
    fun `Memory Promotion Candidates include hit count and sort by hits`() = runBlocking {
        val taskManager = mockk<TaskManager>(relaxed = true)
        every { taskManager.tasks } returns MutableStateFlow(emptyList<TaskEntity>())
        val memoryManager = mockk<com.newoether.agora.data.MemoryManager>()
        // getPromotionCandidatesWithHits sorts by hits descending — the mock returns the
        // already-sorted shape the real manager produces (highest hits first).
        every { memoryManager.getPromotionCandidatesWithHits() } returns listOf(
            com.newoether.agora.data.MemoryManager.MemoryPromotionCandidate("high_hits.md", "desc high", 42),
            com.newoether.agora.data.MemoryManager.MemoryPromotionCandidate("low_hits.md", "desc low", 5),
        )
        val promptBuilder = HeartbeatPromptBuilder(
            taskManager = taskManager,
            loopManager = mockk(relaxed = true),
            conversationRepository = mockk<ConversationRepository>(relaxed = true),
            memoryManager = memoryManager,
            taskRepository = mockk(relaxed = true),
        )
        val prompt = promptBuilder.buildHeartbeatPrompt(customPrompt = "")
        assertTrue("## Memory Promotion Candidates" in prompt)
        assertTrue("(hits: 42)" in prompt)
        assertTrue("(hits: 5)" in prompt)
        // Highest hits render first so the model can prefer pinning them.
        assertTrue(prompt.indexOf("high_hits.md") < prompt.indexOf("low_hits.md"))
    }
}