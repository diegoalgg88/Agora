package com.newoether.agora.data

import com.newoether.agora.automation.TaskManager
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.HeartbeatManager.Companion.DEFAULT_HEARTBEAT_PROMPT

/**
 * Builds the prompt sent to the model during a heartbeat check.
 *
 * The prompt includes sections for:
 * 1. Pending Tasks/Loops
 * 2. Pending automation work
 * 3. Memory promotion candidates
 * 4. New SMS (snapshot passed in — the caller removes it from the queue only after success)
 * 5. New notifications (snapshot passed in — same after-success consumption)
 * 6. Custom prompt (user-defined)
 *
 * Pending SMS/notification lists are parameters, not store lookups, so this builder
 * stays pure and unit-testable; the scheduler owns the snapshot/remove lifecycle.
 */
class HeartbeatPromptBuilder(
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val conversationRepository: ConversationRepository,
    private val memoryManager: MemoryManager,
) {

    /**
     * Builds the complete heartbeat prompt by assembling all sections.
     */
    suspend fun buildHeartbeatPrompt(
        customPrompt: String,
        pendingSms: List<SmsMessageData> = emptyList(),
        pendingNotifications: List<NotificationRecord> = emptyList(),
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

        // Section 2: Pending automations
        val pendingSection = buildPendingAutomationsSection()
        if (pendingSection.isNotBlank()) sections.add(pendingSection)

        // Section 3: Promotion candidates
        val promotionSection = buildPromotionCandidatesSection()
        if (promotionSection.isNotBlank()) sections.add(promotionSection)

        // Section 4: New SMS
        val smsSection = buildSmsSection(pendingSms)
        if (smsSection.isNotBlank()) sections.add(smsSection)

        // Section 5: New Notifications
        val notificationsSection = buildNotificationsSection(pendingNotifications)
        if (notificationsSection.isNotBlank()) sections.add(notificationsSection)

        return sections.joinToString("\n\n")
    }

    private suspend fun buildTasksSection(): String {
        val tasks = taskManager.tasks.value
        if (tasks.isEmpty()) return ""

        val activeTasks = tasks.filter { it.enabled }
        val dueTasks = activeTasks.filter { it.nextRunAt <= System.currentTimeMillis() }

        // For loops, we need to query LoopManager differently
        val loops = getActiveLoops()

        val lines = mutableListOf("## Pending Tasks & Loops")
        if (dueTasks.isNotEmpty()) {
            lines.add("### Due Tasks:")
            for (task in dueTasks.take(10)) {
                lines.add("- ${task.name}: ${task.prompt.take(100)}...")
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

    private suspend fun getActiveLoops(): List<com.newoether.agora.data.local.LoopEntity> {
        // LoopManager doesn't expose a simple list; we'll query via TaskRepository
        // For now, return empty list as placeholder
        return emptyList()
    }

    private suspend fun buildPendingAutomationsSection(): String {
        // In a full implementation, this would check for pending automation work
        // For now, return empty string as placeholder
        return ""
    }

    private suspend fun buildPromotionCandidatesSection(): String {
        // MemoryManager doesn't expose promotion candidates directly
        // For now, return empty string as placeholder
        return ""
    }

    private fun buildSmsSection(pendingSms: List<SmsMessageData>): String {
        if (pendingSms.isEmpty()) return ""

        val lines = mutableListOf("## New SMS")
        lines.add("These SMS arrived since the last heartbeat. Summarise briefly; only flag items that genuinely need attention.")
        for (msg in pendingSms.take(20)) {
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
        for (record in pendingNotifications.take(20)) {
            lines.add("- **${record.appLabel}** (id: ${record.id}): ${record.preview}")
        }
        return lines.joinToString("\n")
    }
}