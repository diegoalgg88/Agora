package com.newoether.agora.data

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.HeartbeatManager.Companion.DEFAULT_HEARTBEAT_PROMPT

/**
 * How many items of each pending channel the heartbeat prompt actually shows. The
 * scheduler consumes EXACTLY this many keys (newest first) after a successful run —
 * consume-vs-shown: an item the model never saw is never silently dropped from the
 * pending queue. Single source of truth shared by the pure builder (render cap) and
 * `HeartbeatScheduler` (snapshot cap), pinned by `HeartbeatPromptConsumeVsShownTest`.
 */
object HeartbeatShownLimits {
    const val SMS = 20
    const val NOTIFICATIONS = 20
    const val EMAILS = 20
}

/**
 * Builds the prompt sent to the model during a heartbeat check.
 *
 * The prompt includes sections for:
 * 1. Pending Tasks/Loops
 * 2. Pending automation work
 * 3. Memory promotion candidates
 * 4. New SMS (snapshot passed in — the caller removes it from the queue only after success)
 * 5. New notifications (snapshot passed in — same after-success consumption)
 * 6. New emails (snapshot passed in — same after-success consumption)
 * 7. Email account status (per-account unread + last sync — trend detection across runs)
 * 8. Custom prompt (user-defined)
 * 9. Previous heartbeat results (last 3 — continuity: trend detection, no repeated alerts)
 *
 * Pending SMS/notification/email lists are parameters, not store lookups, so this builder
 * stays pure and unit-testable; the scheduler owns the snapshot/remove lifecycle. Each
 * pending list is rendered newest-first up to [HeartbeatShownLimits] items, and the
 * scheduler must cap its consume keys to the same limits (see [HeartbeatShownLimits]).
 */
class HeartbeatPromptBuilder(
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val conversationRepository: ConversationRepository,
    private val memoryManager: MemoryManager,
    private val taskRepository: com.newoether.agora.data.repository.TaskRepository,
) {

    /**
     * Builds the complete heartbeat prompt by assembling all sections.
     */
    suspend fun buildHeartbeatPrompt(
        customPrompt: String,
        pendingSms: List<SmsMessageData> = emptyList(),
        pendingNotifications: List<NotificationRecord> = emptyList(),
        pendingEmails: List<EmailPendingData> = emptyList(),
        recentResponses: List<String> = emptyList(),
        emailStatuses: List<EmailAccountStatus> = emptyList(),
    ): String {
        val sections = mutableListOf<String>()

        // Base prompt: custom instructions or default heartbeat prompt
        if (customPrompt.isNotBlank()) {
            sections.add("## Custom Instructions\n$customPrompt")
        } else {
            sections.add(DEFAULT_HEARTBEAT_PROMPT)
        }

        // Section 1: Tasks/Loops
        val tasksSection = buildTasksSection()
        if (tasksSection.isNotBlank()) sections.add(tasksSection)

        // Section 2: Promotion candidates
        val promotionSection = buildPromotionCandidatesSection()
        if (promotionSection.isNotBlank()) sections.add(promotionSection)

        // Section 4: New SMS
        val smsSection = buildSmsSection(pendingSms)
        if (smsSection.isNotBlank()) sections.add(smsSection)

        // Section 5: New Notifications
        val notificationsSection = buildNotificationsSection(pendingNotifications)
        if (notificationsSection.isNotBlank()) sections.add(notificationsSection)

        // Section 6: New Emails
        val emailSection = buildEmailSection(pendingEmails)
        if (emailSection.isNotBlank()) sections.add(emailSection)

        // Section 7: Email account status (trend detection — "still unread since last check")
        val emailStatusSection = buildEmailStatusSection(emailStatuses)
        if (emailStatusSection.isNotBlank()) sections.add(emailStatusSection)

        // Section 8: Previous Heartbeat Results (for continuity)
        val previousSection = buildPreviousHeartbeatSection(recentResponses)
        if (previousSection.isNotBlank()) sections.add(previousSection)

        // Section 9: decision rule, LAST so it is the freshest instruction. The base prompt (the
        // default or the user's) only talks about memories/tasks/follow-ups, so a model reading
        // "nothing needs attention → HEARTBEAT_OK" followed by a list of incoming items had no
        // rule saying a overdue-payment SMS counts as "needs attention", and weaker models
        // answered HEARTBEAT_OK — which also consumes the snapshot, so the item was lost.
        if (smsSection.isNotBlank() || notificationsSection.isNotBlank() || emailSection.isNotBlank()) {
            sections.add(buildResponseRuleSection())
        }

        return sections.joinToString("\n\n")
    }

    private fun buildResponseRuleSection(): String {
        val ok = HeartbeatManager.HEARTBEAT_OK_SENTINEL
        return "## Response Rule\n" +
            "The incoming items above are real messages and notifications from the user's phone. " +
            "If ANY of them is time-sensitive or needs the user's action (payments or bills due or " +
            "overdue, service suspension notices, security or account alerts, deadlines, " +
            "appointments, messages awaiting a reply), do NOT answer $ok: reply with a short alert " +
            "naming the item and what is needed. Answer exactly $ok only when nothing above needs " +
            "the user's attention."
    }

    private suspend fun buildTasksSection(): String {
        val now = System.currentTimeMillis()
        val activeTasks = taskManager.tasks.value.filter { it.enabled }
        val dueTasks = activeTasks.filter { it.nextRunAt <= now }
        // Scheduled tasks run on their own (WorkManager/alarms), so at heartbeat time they are
        // almost never "due": listing only due ones left the section as a bare header and made the
        // model believe nothing was automated. Show what is scheduled, and when.
        val upcomingTasks = activeTasks.filter { it.nextRunAt > now }.sortedBy { it.nextRunAt }

        // Loops are queried independently: a user with loops but no tasks used to get no section
        // at all because the empty-task check returned before they were read.
        val loops = getActiveLoops()

        // Never emit a header with nothing under it.
        if (dueTasks.isEmpty() && upcomingTasks.isEmpty() && loops.isEmpty()) return ""

        val lines = mutableListOf("## Pending Tasks & Loops")
        if (dueTasks.isNotEmpty()) {
            lines.add("### Due Tasks:")
            for (task in dueTasks.take(10)) {
                lines.add("- ${task.name}: ${task.prompt.take(100)}...")
            }
        }
        if (upcomingTasks.isNotEmpty()) {
            lines.add("### Scheduled Tasks (not due yet):")
            lines.add("These run automatically on their schedule; do not run or reschedule them yourself.")
            for (task in upcomingTasks.take(10)) {
                lines.add("- ${task.name}: next run ${formatRunTime(task.nextRunAt)}")
            }
        }
        if (loops.isNotEmpty()) {
            lines.add("### Active Loops:")
            for (loop in loops.take(5)) {
                lines.add("- ${loop.conversationId}: every ${loop.intervalMs / 60000} min")
            }
        }
        return lines.joinToString("\n")
    }

    private fun formatRunTime(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    private suspend fun getActiveLoops(): List<com.newoether.agora.data.local.LoopEntity> {
        return taskRepository.getActiveLoops()
    }

    private suspend fun buildPromotionCandidatesSection(): String {
        val candidates = memoryManager.getPromotionCandidatesWithHits()
        if (candidates.isEmpty()) return ""
        val lines = mutableListOf("## Memory Promotion Candidates")
        lines.add("The following memory files have been accessed frequently. Consider if any of this information should be promoted. Use the `pin_memory_file` tool to pin these memories so they are no longer suggested here. The hit count tells you how strongly reinforced each memory is — prefer pinning the highest-hit entries first.")
        for (c in candidates) {
            val descriptionText = if (c.description.isNotBlank()) ": ${c.description}" else ""
            lines.add("- **${c.name}** (hits: ${c.hitCount})$descriptionText")
        }
        return lines.joinToString("\n")
    }

    private fun buildSmsSection(pendingSms: List<SmsMessageData>): String {
        if (pendingSms.isEmpty()) return ""

        val lines = mutableListOf("## New SMS")
        lines.add("These SMS arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")
        for (msg in pendingSms.take(HeartbeatShownLimits.SMS)) {
            val sender = msg.address.ifBlank { "(unknown sender)" }
            val preview = msg.preview.ifBlank { "" }
            lines.add("- **$sender** (id: ${msg.id})${if (preview.isNotBlank()) ": $preview" else ""}")
        }
        return lines.joinToString("\n")
    }

    private fun buildNotificationsSection(pendingNotifications: List<NotificationRecord>): String {
        if (pendingNotifications.isEmpty()) return ""

        val lines = mutableListOf("## New Notifications")
        lines.add("These notifications arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")

        val sortedNotifications = pendingNotifications.sortedByDescending { it.postedAt }.take(HeartbeatShownLimits.NOTIFICATIONS)
        for (record in sortedNotifications) {
            val titleText = if (record.title.isNotBlank()) ": ${record.title}" else ""
            lines.add("- **${record.appLabel}**$titleText (id: ${record.id}): ${record.preview}")
        }
        return lines.joinToString("\n")
    }

    private fun buildEmailSection(pendingEmails: List<EmailPendingData>): String {
        if (pendingEmails.isEmpty()) return ""

        val lines = mutableListOf("## New Emails")
        lines.add("These emails arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")

        val sortedEmails = pendingEmails.sortedByDescending { it.dateEpochMs }.take(HeartbeatShownLimits.EMAILS)
        for (email in sortedEmails) {
            val sender = email.fromAddress.ifBlank { "(unknown sender)" }
            val subject = email.subject.ifBlank { "(no subject)" }
            val preview = email.preview.ifBlank { "" }
            lines.add("- **$sender** — $subject (uid: ${email.uid}, account: ${email.accountId})${if (preview.isNotBlank()) ": $preview" else ""}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Renders the previous-results continuity section. [recentResponses] must be newest-first
     * (index 0 = most recent) — the scheduler feeds it from the bounded final-response tail
     * query, which never includes blank tool-assembly rows or provider error text.
     */
    private fun buildPreviousHeartbeatSection(recentResponses: List<String>): String {
        if (recentResponses.isEmpty()) return ""
        val lines = mutableListOf(
            "## Previous Heartbeat Results",
            "For context, here are your most recent heartbeat summaries. Use them to track trends, " +
                "avoid repeating an alert you already raised, and detect persistent issues (for " +
                "example, an account whose unread count has not gone down since the last check):",
        )
        for ((i, response) in recentResponses.withIndex()) {
            val label = if (i == 0) "Most recent" else "${i + 1} heartbeats ago"
            lines.add("### $label\n$response")
        }
        return lines.joinToString("\n")
    }

    /**
     * Per-account email status. Unlike the pending (new-arrivals) sections, this runs on
     * EVERY heartbeat with connected accounts — it gives the model a baseline to compare
     * against the previous results above, so it can notice "this account has had the same
     * unread count for three checks" instead of only reacting to brand-new mail.
     */
    private fun buildEmailStatusSection(statuses: List<EmailAccountStatus>): String {
        if (statuses.isEmpty()) return ""
        val lines = mutableListOf("## Email Account Status")
        lines.add("Current state of each connected email account. Compare against the previous heartbeat results to detect persistent issues.")
        for (status in statuses) {
            val syncText = if (status.lastSyncEpochMs > 0) {
                " (last sync: ${formatRunTime(status.lastSyncEpochMs)})"
            } else {
                " (never synced)"
            }
            lines.add("- **${status.email}**: ${status.unreadCount} unread$syncText")
        }
        return lines.joinToString("\n")
    }
}

/** Per-account email status rendered into the `## Email Account Status` section. */
data class EmailAccountStatus(
    val email: String,
    val unreadCount: Int,
    val lastSyncEpochMs: Long,
)