package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_34_35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS assistant_actions (
                id TEXT PRIMARY KEY NOT NULL,
                type TEXT NOT NULL,
                argumentsJson TEXT NOT NULL,
                summary TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                status TEXT NOT NULL,
                lastError TEXT
            )
        """)
    }
}
