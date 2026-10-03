package com.newoether.agora.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable state of a catalog-driven Local Model download.
 *
 * The table is CREATE-only from v38 (MIGRATION_38_39); no existing table is
 * touched. Room is the single durable truth for download state, mirroring the
 * GPT Mobile `LocalModel` row: DOWNLOADING → READY, or FAILED on terminal
 * error/user cancel. The backing file lives under
 * `models/<catalogEntryId>/<commitHash>/` on external app storage.
 *
 * Invariants:
 *   - `catalogEntryId` is the primary identity; one row per catalog entry;
 *   - READY is only trustworthy while the final file exists on disk — the
 *     reconciler deletes rows whose file vanished;
 *   - rows are deliberately excluded from the portable `.agora` archive
 *     (they reference device-local absolute paths).
 */
@Entity(tableName = "local_model_downloads")
data class LocalModelDownloadEntity(
    @PrimaryKey val catalogEntryId: String,
    val commitHash: String,
    val fileName: String,
    val relativeDirectory: String,
    val totalBytes: Long,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
