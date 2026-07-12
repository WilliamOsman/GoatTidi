package com.goattidi.mediasync.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRecordDao {

    @Upsert
    suspend fun upsert(records: List<SyncRecord>)

    @Upsert
    suspend fun upsert(record: SyncRecord)

    @Query("SELECT * FROM sync_records")
    suspend fun getAll(): List<SyncRecord>

    @Query("SELECT * FROM sync_records")
    fun observeAll(): Flow<List<SyncRecord>>

    @Query("SELECT * FROM sync_records WHERE mediaStoreId = :id")
    suspend fun getById(id: Long): SyncRecord?

    @Query("SELECT * FROM sync_records WHERE status = :status")
    suspend fun getByStatus(status: SyncStatus): List<SyncRecord>

    @Query("SELECT * FROM sync_records WHERE driveFileId IS NOT NULL")
    suspend fun getAllWithDriveFile(): List<SyncRecord>

    @Query("DELETE FROM sync_records WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Guarded status transition; rows not currently in [allowedFrom] are left untouched. */
    @Query("UPDATE sync_records SET status = :newStatus WHERE mediaStoreId IN (:ids) AND status IN (:allowedFrom)")
    suspend fun transition(ids: List<Long>, newStatus: SyncStatus, allowedFrom: List<SyncStatus>): Int
}
