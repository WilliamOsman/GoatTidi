package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.drive.withRetry
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.repo.SyncStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-up-space support (§4.5). The actual deletion is the UI's job (it must route
 * through MediaStore.createDeleteRequest on API 30+); this engine owns the gate:
 *
 * Invariant #3 — a local file may only be deleted when a FRESH check proves its
 * current bytes are on Drive: freshly recomputed local MD5 == freshly fetched Drive
 * MD5. A stale `status == SYNCED` is never sufficient.
 */
@Singleton
class ReclaimEngine @Inject constructor(
    private val repository: SyncStateRepository,
    private val client: DriveClient,
    private val localFiles: LocalFiles
) {
    sealed class Gate {
        object Safe : Gate()
        data class Blocked(val reason: String) : Gate()
    }

    /** Verified-synced files, biggest first — candidates for the free-up-space view. */
    suspend fun candidates(): List<SyncRecord> = candidatesFrom(repository.syncedRecords())

    // IO dispatcher: hashing streams entire files and must never run on Main
    suspend fun confirmSafeToDelete(
        mediaStoreId: Long,
        retryPolicy: RetryPolicy = RetryPolicy()
    ): Gate = withContext(Dispatchers.IO) {
        confirmSafeToDeleteBlocking(mediaStoreId, retryPolicy)
    }

    private suspend fun confirmSafeToDeleteBlocking(mediaStoreId: Long, retryPolicy: RetryPolicy): Gate {
        val record = repository.getById(mediaStoreId)
            ?: return Gate.Blocked("No sync record for this file")
        if (record.status != SyncStatus.SYNCED || record.driveFileId == null) {
            return Gate.Blocked("File is not verified as synced")
        }
        if (localFiles.stat(record) == null) {
            return Gate.Blocked("Local file is already gone")
        }

        // Fresh remote check: the Drive copy must still exist with a checksum
        val remote = try {
            withRetry(retryPolicy) { client.getFile(record.driveFileId) }
        } catch (e: DriveException.NotFound) {
            repository.markOrphaned(mediaStoreId)
            return Gate.Blocked("File no longer exists on Drive")
        }
        if (remote.trashed) {
            repository.markOrphaned(mediaStoreId)
            return Gate.Blocked("Drive copy is in the trash")
        }
        val remoteMd5 = remote.md5Checksum
            ?: return Gate.Blocked("Drive has not produced a checksum for this file")

        // Fresh local check: hash the bytes as they are RIGHT NOW, not the cached value
        val freshLocalMd5 = Md5.of(localFiles.open(record, 0))
        if (!freshLocalMd5.equals(remoteMd5, ignoreCase = true)) {
            repository.markModifiedSinceUpload(mediaStoreId)
            return Gate.Blocked("Local file was modified since upload — current bytes are not on Drive")
        }
        return Gate.Safe
    }

    companion object {
        /**
         * The free-up-space list from any set of records. The screen derives it from the live
         * record Flow, so it updates the moment a Verify, upload, or delete changes a status.
         */
        fun candidatesFrom(records: List<SyncRecord>): List<SyncRecord> =
            records.filter { it.status == SyncStatus.SYNCED }.sortedByDescending { it.sizeBytes }
    }
}
