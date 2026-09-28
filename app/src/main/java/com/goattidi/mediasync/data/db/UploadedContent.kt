package com.goattidi.mediasync.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Upload ledger: every verified upload is recorded here by content hash, and the
 * entry OUTLIVES the media record (which mirrors the device and is dropped when a
 * file moves or disappears locally). Before uploading, the worker checks this
 * ledger — identical bytes already proven on Drive are adopted, not re-uploaded
 * (content dedup, spec §4.4). This is what makes locally-moved or renamed files
 * flip straight back to SYNCED instead of re-uploading gigabytes.
 */
@Entity(tableName = "uploaded_content")
data class UploadedContent(
    @PrimaryKey val md5: String,
    val driveFileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val uploadedAt: Long
)

@Dao
interface UploadedContentDao {

    @Upsert
    suspend fun upsert(entry: UploadedContent)

    @Query("SELECT * FROM uploaded_content WHERE md5 = :md5")
    suspend fun getByMd5(md5: String): UploadedContent?

    @Query("DELETE FROM uploaded_content WHERE md5 = :md5")
    suspend fun deleteByMd5(md5: String)

    @Query("SELECT COUNT(*) FROM uploaded_content")
    suspend fun count(): Int
}
