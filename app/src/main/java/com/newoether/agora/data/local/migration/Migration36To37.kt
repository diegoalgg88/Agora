package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MIGRATION_36_37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Run origin kind ("chat", "heartbeat", "task", ...). Nullable: pre-v37 runs keep null
        // and render unchanged; purely additive — no existing column is touched.
        db.execSQL("ALTER TABLE runs ADD COLUMN requestKind TEXT")
    }
}
