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
}