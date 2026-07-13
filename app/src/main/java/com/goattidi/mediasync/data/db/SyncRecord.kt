package com.goattidi.mediasync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncStatus {
    NOT_UPLOADED, QUEUED, UPLOADING, SYNCED,
    MODIFIED_SINCE_UPLOAD, FAILED, ORPHANED
}

enum class MediaType { IMAGE, VIDEO, AUDIO }

/**
 * Source of truth for a media file's sync state (invariant #4: all state lives here).
 *
 * The cached [localMd5] is only trustworthy for the exact bytes it was computed from,
 * so the size and date_modified observed at computation time are snapshotted alongside
 * it ([md5SizeBytes], [md5DateModified]). If MediaStore later reports different values,
 * the MD5 must be discarded (invariant #2).
 */
@Entity(tableName = "sync_records")
data class SyncRecord(
    @PrimaryKey val mediaStoreId: Long,
    val localUri: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val mediaType: MediaType,
    val dateTaken: Long,
    val dateModified: Long,
    /** Playback length in ms for video/audio; 0 = unknown or not applicable. */
    val durationMs: Long = 0,
    val localMd5: String?,
    val md5SizeBytes: Long?,
    val md5DateModified: Long?,
    val driveFileId: String?,
    val driveMd5: String?,
    val uploadedAt: Long?,
    val status: SyncStatus,
    val failureReason: String?,
    val resumeSessionUri: String?
) {
    /** True if the cached MD5 still describes the file MediaStore currently reports. */
    val md5IsCurrent: Boolean
        get() = localMd5 != null && md5SizeBytes == sizeBytes && md5DateModified == dateModified
}
