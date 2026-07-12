package com.goattidi.mediasync.data.repo

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncRecordDao
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.media.MediaStoreScanner
import com.goattidi.mediasync.data.media.ScannedMedia
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncStateRepository @Inject constructor(
    private val dao: SyncRecordDao,
    private val scanner: MediaStoreScanner
) {

    fun observeAll(): Flow<List<SyncRecord>> = dao.observeAll()

    suspend fun scanAndReconcile() = reconcile(scanner.scanAll())

    /**
     * Merges a MediaStore scan into the DB. New files become NOT_UPLOADED; for known
     * files, a size or date_modified change invalidates the cached MD5 (invariant #2)
     * and demotes SYNCED to MODIFIED_SINCE_UPLOAD.
     */
    suspend fun reconcile(scanned: List<ScannedMedia>) {
        val existing = dao.getAll().associateBy { it.mediaStoreId }
        // Files gone from MediaStore are gone from the device — drop their records
        // (the gallery mirrors what's on the phone; Drive copies are unaffected)
        val gone = existing.keys - scanned.map { it.mediaStoreId }.toSet()
        if (gone.isNotEmpty()) dao.deleteByIds(gone.toList())
        val upserts = scanned.map { s ->
            val prev = existing[s.mediaStoreId]
            if (prev == null) newRecord(s) else merge(prev, s)
        }
        if (upserts.isNotEmpty()) dao.upsert(upserts)
    }

    /** Stores a freshly computed MD5 together with the file identity it was computed from. */
    suspend fun cacheMd5(mediaStoreId: Long, md5: String, sizeBytes: Long, dateModified: Long) {
        update(mediaStoreId) { it.copy(localMd5 = md5, md5SizeBytes = sizeBytes, md5DateModified = dateModified) }
    }

    // ---- Queue transitions (all state lives in the DB — invariant #4) ----

    suspend fun getById(mediaStoreId: Long): SyncRecord? = dao.getById(mediaStoreId)

    /** Queues files for upload. Only NOT_UPLOADED / FAILED / MODIFIED_SINCE_UPLOAD may enter the queue. */
    suspend fun enqueue(ids: List<Long>): Int = dao.transition(
        ids, SyncStatus.QUEUED,
        listOf(SyncStatus.NOT_UPLOADED, SyncStatus.FAILED, SyncStatus.MODIFIED_SINCE_UPLOAD)
    )

    /**
     * Records the worker should process. Stale UPLOADING rows (process died mid-upload)
     * come first so their persisted sessions resume before new uploads start.
     */
    suspend fun uploadBatch(): List<SyncRecord> =
        dao.getByStatus(SyncStatus.UPLOADING) + dao.getByStatus(SyncStatus.QUEUED)

    suspend fun markUploading(id: Long) =
        update(id) { it.copy(status = SyncStatus.UPLOADING, failureReason = null) }

    suspend fun saveSessionUri(id: Long, sessionUri: String) =
        update(id) { it.copy(resumeSessionUri = sessionUri) }

    suspend fun markSynced(id: Long, driveFileId: String, driveMd5: String, uploadedAt: Long) =
        update(id) {
            it.copy(
                status = SyncStatus.SYNCED, driveFileId = driveFileId, driveMd5 = driveMd5,
                uploadedAt = uploadedAt, resumeSessionUri = null, failureReason = null
            )
        }

    suspend fun markFailed(id: Long, reason: String, driveFileId: String? = null) =
        update(id) {
            it.copy(
                status = SyncStatus.FAILED, failureReason = reason, resumeSessionUri = null,
                driveFileId = driveFileId ?: it.driveFileId
            )
        }

    /** Upload finished but Drive hasn't produced a checksum yet: keep UPLOADING, drop the session. */
    suspend fun markUnverified(id: Long, driveFileId: String) =
        update(id) { it.copy(driveFileId = driveFileId, resumeSessionUri = null) }

    /** Puts an item back in the queue (retryable failure); keeps the resumable session. */
    suspend fun requeue(id: Long, reason: String? = null) =
        update(id) { it.copy(status = SyncStatus.QUEUED, failureReason = reason) }

    /** File changed mid-flight (invariant #2): discard MD5 + session, upload again from scratch. */
    suspend fun requeueModified(id: Long) =
        update(id) {
            it.copy(
                status = SyncStatus.QUEUED, localMd5 = null, md5SizeBytes = null,
                md5DateModified = null, resumeSessionUri = null
            )
        }

    // ---- Verify / reclaim support ----

    suspend fun recordsWithDriveFile(): List<SyncRecord> = dao.getAllWithDriveFile()

    suspend fun syncedRecords(): List<SyncRecord> = dao.getByStatus(SyncStatus.SYNCED)

    /** Drive-side file is gone (deleted or trashed) — the upload no longer counts. */
    suspend fun markOrphaned(id: Long) =
        update(id) { it.copy(status = SyncStatus.ORPHANED) }

    /** Local bytes no longer match what's on Drive; cached MD5 is untrustworthy. */
    suspend fun markModifiedSinceUpload(id: Long) =
        update(id) {
            it.copy(
                status = SyncStatus.MODIFIED_SINCE_UPLOAD,
                localMd5 = null, md5SizeBytes = null, md5DateModified = null
            )
        }

    suspend fun confirmSynced(id: Long, driveMd5: String) =
        update(id) {
            it.copy(
                status = SyncStatus.SYNCED, driveMd5 = driveMd5,
                uploadedAt = it.uploadedAt ?: System.currentTimeMillis(), failureReason = null
            )
        }

    private suspend fun update(id: Long, transform: (SyncRecord) -> SyncRecord) {
        dao.getById(id)?.let { dao.upsert(transform(it)) }
    }

    private fun newRecord(s: ScannedMedia) = SyncRecord(
        mediaStoreId = s.mediaStoreId,
        localUri = s.contentUri,
        filePath = s.filePath,
        fileName = s.fileName,
        sizeBytes = s.sizeBytes,
        mimeType = s.mimeType,
        mediaType = s.mediaType,
        dateTaken = s.dateTaken,
        dateModified = s.dateModified,
        localMd5 = null,
        md5SizeBytes = null,
        md5DateModified = null,
        driveFileId = null,
        driveMd5 = null,
        uploadedAt = null,
        status = SyncStatus.NOT_UPLOADED,
        failureReason = null,
        resumeSessionUri = null
    )

    private fun merge(prev: SyncRecord, s: ScannedMedia): SyncRecord {
        val md5StillValid = prev.localMd5 != null &&
            prev.md5SizeBytes == s.sizeBytes &&
            prev.md5DateModified == s.dateModified
        val invalidated = prev.localMd5 != null && !md5StillValid
        return prev.copy(
            localUri = s.contentUri,
            filePath = s.filePath,
            fileName = s.fileName,
            sizeBytes = s.sizeBytes,
            mimeType = s.mimeType,
            dateTaken = s.dateTaken,
            dateModified = s.dateModified,
            localMd5 = if (md5StillValid) prev.localMd5 else null,
            md5SizeBytes = if (md5StillValid) prev.md5SizeBytes else null,
            md5DateModified = if (md5StillValid) prev.md5DateModified else null,
            status = if (invalidated && prev.status == SyncStatus.SYNCED) {
                SyncStatus.MODIFIED_SINCE_UPLOAD
            } else {
                prev.status
            }
        )
    }
}
