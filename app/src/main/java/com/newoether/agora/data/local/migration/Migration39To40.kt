package com.newoether.agora.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v39 → v40: adds `task_confirmations.resolvedAtEpochMs`, the durable timestamp of a USER
 * resolution (Confirm or Dismiss via the card / notification actions). It feeds the
 * heartbeat's acknowledgement window: an alert the user confirmed is listed in the next
 * prompts' "Already Acknowledged" section so the model stops re-alerting it. Superseded
 * rows (heartbeat anti-stacking) deliberately keep the column NULL — supersession is not
 * a user resolution and must not silence the alert.
 *
 * Additive only: nullable column, no default, no existing row is touched — no index is
 * needed because the table is bounded by design (≤20 PENDING rows, tombstones purged
 * after 7 days).
 */
object MIGRATION_39_40 : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE task_confirmations ADD COLUMN resolvedAtEpochMs INTEGER",
        )
    }
}
