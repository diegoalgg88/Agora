package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v42 → v43: conversaciones pineadas del drawer. `isPinned` default 0 — CREATE-only en
 * columnas existentes, ninguna fila ni tabla previa se toca.
 */
object MIGRATION_42_43 : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0",
        )
    }
}
