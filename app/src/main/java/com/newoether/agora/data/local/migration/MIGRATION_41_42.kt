package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v41 → v42: watermark `dataChangedAt` de conversaciones para el archive incremental v5.
 * CREATE-only en columnas existentes — ninguna fila ni tabla previa se toca; todas las
 * conversaciones legadas nacen con watermark 0.
 */
object MIGRATION_41_42 : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations ADD COLUMN dataChangedAt INTEGER NOT NULL DEFAULT 0",
        )
    }
}
