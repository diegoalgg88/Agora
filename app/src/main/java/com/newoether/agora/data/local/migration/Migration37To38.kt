package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v37 → v38: introduces `task_confirmations`, the durable owner of rich task
 * confirmations (plan PLAN-20261002-TASK-CONFIRM).
 *
 * Purely CREATE-only — no existing table or column is touched. The index names match
 * what Room derives from the @Entity declarations, so post-migration schema validation
 * passes. No SQL CHECK constraints: Room ≥2.7 compares them during validation and the
 * closed-enum invariant is owned by the typed enums in TaskConfirmationEntity.kt.
 */
object MIGRATION_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS task_confirmations (
                id TEXT NOT NULL,
                sourceType TEXT NOT NULL,
                conversationId TEXT NOT NULL,
                modelMessageId TEXT,
                title TEXT NOT NULL,
                bodyText TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                remindAtEpochMs INTEGER,
                status TEXT NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
                index_task_confirmations_sourceType_conversationId_modelMessageId
            ON task_confirmations (sourceType, conversationId, modelMessageId)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                index_task_confirmations_status_remindAtEpochMs
            ON task_confirmations (status, remindAtEpochMs)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                index_task_confirmations_createdAtEpochMs
            ON task_confirmations (createdAtEpochMs)
            """.trimIndent(),
        )
    }
}
