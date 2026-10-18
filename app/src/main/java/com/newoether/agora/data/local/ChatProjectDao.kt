package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A user-created folder that groups conversations in the drawer. Pure organization metadata:
 * it never participates in generation, context assembly or Run ownership.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Drawer presentation state, persisted so the list looks the same after a restart. */
    val collapsed: Boolean = false,
)

/**
 * Membership of one conversation in at most one project (the primary key enforces it).
 * Deleting the conversation or the project removes only this row — deleting a project never
 * deletes its conversations, they simply return to the ungrouped list.
 */
@Entity(
    tableName = "project_conversations",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["projectId"])],
)
data class ProjectConversationEntity(
    @PrimaryKey val conversationId: String,
    val projectId: String,
)

/** Project CRUD and membership queries inherited by [ChatDao] (the sole `@Dao`). */
interface ChatProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt ASC, id ASC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project_conversations")
    fun observeProjectMemberships(): Flow<List<ProjectConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name WHERE id = :projectId")
    suspend fun renameProject(projectId: String, name: String): Int

    @Query("UPDATE projects SET collapsed = :collapsed WHERE id = :projectId")
    suspend fun setProjectCollapsed(projectId: String, collapsed: Boolean): Int

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String): Int

    /** Only ids that still exist in `conversations` are assigned; stale ids are ignored. */
    @Query(
        """
        INSERT OR REPLACE INTO project_conversations (conversationId, projectId)
        SELECT id, :projectId FROM conversations WHERE id IN (:conversationIds)
        """
    )
    suspend fun assignConversationsToProject(projectId: String, conversationIds: List<String>)

    @Query("DELETE FROM project_conversations WHERE conversationId IN (:conversationIds)")
    suspend fun removeConversationsFromProjects(conversationIds: List<String>): Int

    @Transaction
    suspend fun createProjectWithConversations(
        project: ProjectEntity,
        conversationIds: List<String>,
    ) {
        insertProject(project)
        conversationIds.chunked(PROJECT_ASSIGN_CHUNK).forEach {
            assignConversationsToProject(project.id, it)
        }
    }

    companion object {
        /** Stays far below SQLite's bound-variable limit. */
        const val PROJECT_ASSIGN_CHUNK = 500
    }
}
