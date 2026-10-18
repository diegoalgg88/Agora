package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v40 → v41: introduces drawer projects. `projects` holds the folders and
 * `project_conversations` maps a conversation to at most one project (PK on conversationId).
 * Both foreign keys CASCADE, but only the membership row is ever removed: deleting a project
 * leaves its conversations intact.
 *
 * CREATE-only — no existing table or column is touched, so the conversation-list projection
 * and every legacy row stay exactly as they were.
 */
object MIGRATION_40_41 : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                collapsed INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS project_conversations (
                conversationId TEXT NOT NULL,
                projectId TEXT NOT NULL,
                PRIMARY KEY(conversationId),
                FOREIGN KEY(conversationId) REFERENCES conversations(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(projectId) REFERENCES projects(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_project_conversations_projectId
            ON project_conversations (projectId)
            """.trimIndent(),
        )
    }
}
