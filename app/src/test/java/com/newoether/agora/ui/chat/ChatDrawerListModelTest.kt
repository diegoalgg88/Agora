package com.newoether.agora.ui.chat

import com.newoether.agora.data.local.ProjectEntity
import com.newoether.agora.model.ChatConversation
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatDrawerListModelTest {
    private fun chat(id: String) = ChatConversation(id = id, title = id)
    private fun project(id: String, collapsed: Boolean = false) =
        ProjectEntity(id = id, name = id, createdAt = 0L, collapsed = collapsed)

    private fun keys(rows: List<DrawerRow>) = rows.map { it.key }

    @Test
    fun projectsComeFirstThenUngroupedKeepingRecencyOrder() {
        val rows = buildDrawerRows(
            conversations = listOf(chat("c1"), chat("c2"), chat("c3")),
            projects = listOf(project("p1")),
            memberships = mapOf("c2" to "p1"),
        )
        assertEquals(
            listOf("project:p1", "conversation:c2", "conversation:c1", "conversation:c3"),
            keys(rows),
        )
    }

    @Test
    fun collapsedProjectHidesChatsButStillCountsThem() {
        val rows = buildDrawerRows(
            conversations = listOf(chat("c1"), chat("c2")),
            projects = listOf(project("p1", collapsed = true)),
            memberships = mapOf("c1" to "p1", "c2" to "p1"),
        )
        assertEquals(listOf("project:p1"), keys(rows))
        assertEquals(listOf("c1", "c2"), (rows.single() as DrawerRow.Project).chatIds)
    }

    @Test
    fun membershipToUnknownProjectFallsBackToUngrouped() {
        val rows = buildDrawerRows(
            conversations = listOf(chat("c1")),
            projects = emptyList(),
            memberships = mapOf("c1" to "ghost"),
        )
        assertEquals(listOf("conversation:c1"), keys(rows))
        assertEquals(null, (rows.single() as DrawerRow.Chat).projectId)
    }

    @Test
    fun emptyProjectStillShowsItsHeader() {
        val rows = buildDrawerRows(emptyList(), listOf(project("p1")), emptyMap())
        assertEquals(listOf("project:p1"), keys(rows))
    }

    @Test
    fun everyConversationAppearsExactlyOnce() {
        val conversations = (1..6).map { chat("c$it") }
        val rows = buildDrawerRows(
            conversations = conversations,
            projects = listOf(project("p1"), project("p2", collapsed = true)),
            memberships = mapOf("c1" to "p1", "c2" to "p2", "c3" to "p1"),
        )
        val visible = rows.filterIsInstance<DrawerRow.Chat>().map { it.conversation.id }
        // c2 lives in the collapsed project, so it is the only one not rendered.
        assertEquals(setOf("c1", "c3", "c4", "c5", "c6"), visible.toSet())
        assertEquals(visible.size, visible.toSet().size)
    }
}
