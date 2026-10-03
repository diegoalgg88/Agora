package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v38 → v39: introduces `local_model_downloads`, the durable owner of
 * catalog-driven Local Model download state (GPT Mobile feature port).
 *
 * Purely CREATE-only — no existing table or column is touched. The index name
 * matches what Room derives from the @Entity declaration so post-migration
 * schema validation passes.
 */
object MIGRATION_38_39 : Migration(38, 39) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_model_downloads (
                catalogEntryId TEXT NOT NULL,
                commitHash TEXT NOT NULL,
                fileName TEXT NOT NULL,
                relativeDirectory TEXT NOT NULL,
                totalBytes INTEGER NOT NULL,
                status TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(catalogEntryId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
                index_local_model_downloads_status
            ON local_model_downloads (status)
            """.trimIndent(),
        )
    }
}
