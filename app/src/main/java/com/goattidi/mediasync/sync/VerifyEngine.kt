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

    /**
     * Rebuilds the upload ledger from Drive itself: pages through every file the
     * app can see (its own uploads, in whatever folder they live now) and records
     * their checksums. Restores cross-folder dedup after a reinstall or DB loss.
     * Returns the number of ledger entries imported.
     */
    suspend fun importLedgerFromDrive(retryPolicy: RetryPolicy = RetryPolicy()): Int {
        var imported = 0
        var pageToken: String? = null
        val now = System.currentTimeMillis()
        do {
            val page = withRetry(retryPolicy) { client.listFiles(pageToken) }
            for (file in page.files) {
                val md5 = file.md5Checksum ?: continue // Docs/Sheets etc. have no checksum
                repository.recordUploaded(
                    md5, file.id, file.name ?: "", file.size?.toLongOrNull() ?: 0L, now
                )
                imported++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return imported
    }

    /**
     * Indexes one user-designated Drive folder tree (subfolders included) into the
     * dedup ledger — this is how uploads from OTHER tools (rclone, Drive web) become
     * known duplicates. Requires the read-only scope; by design this is the only
     * place the app reads anything it didn't create, and it never leaves this tree.
     *
     * Returns the number of files indexed, or null if [folderPath] doesn't exist.
     */
    suspend fun importExternalFolder(folderPath: String, retryPolicy: RetryPolicy = RetryPolicy()): Int? {
        val rootId = withRetry(retryPolicy) { client.resolveFolderPath(folderPath) } ?: return null
        var imported = 0
        val now = System.currentTimeMillis()
        val queue = ArrayDeque(listOf(rootId))
        val seen = mutableSetOf(rootId) // shortcuts/moves must not loop the walk
        while (queue.isNotEmpty()) {
            val folderId = queue.removeFirst()
            var pageToken: String? = null
            do {
                val page = withRetry(retryPolicy) { client.listChildren(folderId, pageToken) }
                for (file in page.files) {
                    if (file.isFolder) {
                        if (seen.add(file.id)) queue.addLast(file.id)
                    } else {
                        val md5 = file.md5Checksum ?: continue
                        repository.recordUploaded(
                            md5, file.id, file.name ?: "", file.size?.toLongOrNull() ?: 0L, now
                        )
                        imported++
                    }
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
        }
        return imported
    }
}
