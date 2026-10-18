package com.newoether.agora.ui.chat

import com.newoether.agora.data.local.ProjectEntity
import com.newoether.agora.model.ChatConversation

/** One flat LazyColumn row of the drawer list. */
internal sealed interface DrawerRow {
    val key: String

    /** A section heading ("Pinned" / "Conversations"). */
    data class Section(val pinned: Boolean) : DrawerRow {
        override val key: String get() = if (pinned) "section:pinned" else "section:conversations"
    }

    /** A project header. [chatIds] includes chats hidden by a collapsed project. */
    data class Project(
        val project: ProjectEntity,
        val chatIds: List<String>,
    ) : DrawerRow {
        override val key: String get() = "project:${project.id}"
    }

    /** A conversation; [projectId] is null for ungrouped chats. */
    data class Chat(
        val conversation: ChatConversation,
        val projectId: String?,
    ) : DrawerRow {
        // Same prefix the list-scroll effects already rely on.
        override val key: String get() = "conversation:${conversation.id}"
    }
}

/** At most one project dialog is open at a time. */
internal sealed interface ProjectDialog {
    data class Create(val assignIds: Set<String>) : ProjectDialog
    data class Rename(val id: String, val name: String) : ProjectDialog
    data class Delete(val id: String, val name: String) : ProjectDialog
    data class Move(val ids: Set<String>) : ProjectDialog
    data object BulkDelete : ProjectDialog
}

/**
 * Pinned conversations first under a "Pinned" heading (recency order inside the group), then
 * projects (creation order), each followed by its chats unless collapsed, then every ungrouped
 * chat under a "Conversations" heading. [conversations] keeps its incoming recency order inside
 * each group. A membership that points to an unknown project is treated as ungrouped, so a chat
 * can never disappear from the list.
 */
internal fun buildDrawerRows(
    conversations: List<ChatConversation>,
    projects: List<ProjectEntity>,
    memberships: Map<String, String>,
): List<DrawerRow> {
    val (pinned, ordinary) = conversations.partition { it.isPinned }
    val knownProjectIds = projects.mapTo(HashSet()) { it.id }
    val byProject = HashMap<String, MutableList<ChatConversation>>()
    val ungrouped = ArrayList<ChatConversation>()
    for (conversation in ordinary) {
        val projectId = memberships[conversation.id]?.takeIf { it in knownProjectIds }
        if (projectId == null) ungrouped += conversation
        else byProject.getOrPut(projectId) { ArrayList() } += conversation
    }
    val rows = ArrayList<DrawerRow>(conversations.size + projects.size + 2)
    if (pinned.isNotEmpty()) {
        rows += DrawerRow.Section(pinned = true)
        pinned.forEach { rows += DrawerRow.Chat(it, null) }
    }
    for (project in projects) {
        val chats = byProject[project.id].orEmpty()
        rows += DrawerRow.Project(project, chats.map { it.id })
        if (!project.collapsed) chats.forEach { rows += DrawerRow.Chat(it, project.id) }
    }
    if (pinned.isNotEmpty() && (ungrouped.isNotEmpty() || projects.isNotEmpty())) {
        rows += DrawerRow.Section(pinned = false)
    }
    ungrouped.forEach { rows += DrawerRow.Chat(it, null) }
    return rows
}
