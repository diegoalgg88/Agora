package com.newoether.agora.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Durable state for catalog-driven Local Model downloads (table
 * `local_model_downloads`, v39). One row per catalog entry; the worker is the
 * only writer of status transitions, the repository is the writer of row
 * creation/deletion.
 */
@Dao
interface LocalModelDownloadDao {
    @Query("SELECT * FROM local_model_downloads")
    fun observeAll(): Flow<List<LocalModelDownloadEntity>>

    @Query("SELECT * FROM local_model_downloads WHERE catalogEntryId = :catalogEntryId")
    suspend fun getById(catalogEntryId: String): LocalModelDownloadEntity?

    @Query("SELECT * FROM local_model_downloads")
    suspend fun getAll(): List<LocalModelDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalModelDownloadEntity)

    @Query(
        """
        UPDATE local_model_downloads
        SET status = :status, updatedAtEpochMs = :updatedAtEpochMs
        WHERE catalogEntryId = :catalogEntryId
        """
    )
    suspend fun updateStatus(catalogEntryId: String, status: String, updatedAtEpochMs: Long)

    @Query("DELETE FROM local_model_downloads WHERE catalogEntryId = :catalogEntryId")
    suspend fun deleteById(catalogEntryId: String)
}
