package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.drive.withRetry
import com.goattidi.mediasync.data.repo.SyncStateRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Verify action (§4.5): batch-checks every record that claims a Drive file,
 * updating statuses from what Drive actually reports — never from local assumptions.
 */
@Singleton
class VerifyEngine @Inject constructor(
    private val repository: SyncStateRepository,
    private val client: DriveClient
) {
    data class Summary(
        val checked: Int,
        val confirmedSynced: Int,
        val orphaned: Int,
        val modified: Int,
        val stillUnverified: Int
    )

    suspend fun verifyAll(retryPolicy: RetryPolicy = RetryPolicy()): Summary {
        var confirmed = 0
        var orphaned = 0
        var modified = 0
        var unverified = 0
        val candidates = repository.recordsWithDriveFile()

        for (record in candidates) {
            val driveFileId = record.driveFileId ?: continue
            val remote = try {
                withRetry(retryPolicy) { client.getFile(driveFileId) }
            } catch (e: DriveException.NotFound) {
                repository.markOrphaned(record.mediaStoreId)
                orphaned++
                continue
            }
            if (remote.trashed) {
                repository.markOrphaned(record.mediaStoreId)
                orphaned++
                continue
            }
            val remoteMd5 = remote.md5Checksum
            if (remoteMd5 == null) {
                // Drive still hasn't produced a checksum — cannot be promoted (invariant #1)
                unverified++
                continue
            }
            when {
                // Only a current local MD5 can be compared against Drive's
                !record.md5IsCurrent -> unverified++
                remoteMd5.equals(record.localMd5, ignoreCase = true) -> {
                    repository.confirmSynced(record.mediaStoreId, remoteMd5)
                    confirmed++
                }
                else -> {
                    repository.markModifiedSinceUpload(record.mediaStoreId)
                    modified++
                }
            }
        }
        return Summary(candidates.size, confirmed, orphaned, modified, unverified)
    }
}
