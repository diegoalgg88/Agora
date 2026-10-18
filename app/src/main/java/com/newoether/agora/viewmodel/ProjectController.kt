package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.ChatProjectDao
import com.newoether.agora.data.local.ProjectEntity
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drawer-project organization (folders of conversations). Owns only the two small project
 * tables; it never touches messages, Runs or generation state, so it cannot interfere with
 * the conversation pipeline. Every mutation is one DAO call (or one DAO transaction).
 */
class ProjectController(
    private val dao: ChatProjectDao,
    private val scope: CoroutineScope,
) {
    val projects: StateFlow<List<ProjectEntity>> = dao.observeProjects()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** conversationId -> projectId. */
    val memberships: StateFlow<Map<String, String>> = dao.observeProjectMemberships()
        .map { rows -> rows.associate { it.conversationId to it.projectId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun create(name: String, conversationIds: Set<String> = emptySet()) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val project = ProjectEntity(id = UUID.randomUUID().toString(), name = clean)
        launchSafely("create") {
            dao.createProjectWithConversations(project, conversationIds.toList())
        }
    }

    fun rename(projectId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        launchSafely("rename") { dao.renameProject(projectId, clean) }
    }

    fun setCollapsed(projectId: String, collapsed: Boolean) {
        launchSafely("collapse") { dao.setProjectCollapsed(projectId, collapsed) }
    }

    /** Deletes the folder only; its conversations return to the ungrouped list. */
    fun delete(projectId: String) {
        launchSafely("delete") { dao.deleteProject(projectId) }
    }

    fun assign(projectId: String, conversationIds: Set<String>) {
        if (conversationIds.isEmpty()) return
        launchSafely("assign") {
            conversationIds.chunked(ChatProjectDao.PROJECT_ASSIGN_CHUNK).forEach {
                dao.assignConversationsToProject(projectId, it)
            }
        }
    }

    fun unassign(conversationIds: Set<String>) {
        if (conversationIds.isEmpty()) return
        launchSafely("unassign") {
            conversationIds.chunked(ChatProjectDao.PROJECT_ASSIGN_CHUNK).forEach {
                dao.removeConversationsFromProjects(it)
            }
        }
    }

    private fun launchSafely(label: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A project deleted concurrently can fail the FK check; state stays consistent.
                runCatching { DebugLog.e("ProjectController", "Project $label failed", error) }
            }
        }
    }
}
