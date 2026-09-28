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

    /** Counts from a sync scan. [inFolder] is null when the chosen folder is gone from Drive. */
    data class SyncScanResult(val own: Int, val inFolder: Int?)

    /**
     * Sync scan for a chosen folder — how uploads from OTHER tools (rclone, Drive web)
     * become known duplicates — together with the app's own uploads, in one pass over
     * Drive. Requires the read-only scope; files outside the folder tree are skipped
     * unless this app created them.
     *
     * Under drive.readonly, listing the app's own uploads already pages through the
     * whole Drive, so those same pages supply the folder's files. Which files sit in the
     * folder comes from one listing of every folder, not a walk of the tree with a
     * request per subfolder — that walk dominated scan time on real Drives.
     */
    suspend fun scanOwnUploadsAndFolder(folderId: String, retryPolicy: RetryPolicy = RetryPolicy()): SyncScanResult {
        val folder = try {
            withRetry(retryPolicy) { client.getFile(folderId, fields = "id,trashed") }
        } catch (e: DriveException.NotFound) {
            null
        }
        if (folder == null || folder.trashed) return SyncScanResult(importLedgerFromDrive(retryPolicy), null)

        val parentsOf = HashMap<String, List<String>>()
        var pageToken: String? = null
        do {
            val page = withRetry(retryPolicy) { client.listAllFolders(pageToken) }
            for (f in page.files) parentsOf[f.id] = f.parents
            pageToken = page.nextPageToken
        } while (pageToken != null)
        val tree = FolderTree(folderId, parentsOf)

        var own = 0
        var inFolder = 0
        val now = System.currentTimeMillis()
        do {
            val page = withRetry(retryPolicy) { client.listAllFiles(pageToken) }
            for (file in page.files) {
                val md5 = file.md5Checksum ?: continue // Docs/Sheets etc. have no checksum
                val mine = file.isAppAuthorized
                val inside = file.parents.any(tree::contains)
                if (!mine && !inside) continue
                repository.recordUploaded(md5, file.id, file.name ?: "", file.size?.toLongOrNull() ?: 0L, now)
                if (mine) own++
                if (inside) inFolder++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return SyncScanResult(own, inFolder)
    }

    /**
     * Sync scan for the entire Drive: one flat listing of the user's own files, which
     * also covers this app's uploads. Files merely shared with the user are left out.
     */
    suspend fun importEntireDrive(retryPolicy: RetryPolicy = RetryPolicy()): Int {
        var imported = 0
        var pageToken: String? = null
        val now = System.currentTimeMillis()
        do {
            val page = withRetry(retryPolicy) { client.listOwnedFiles(pageToken) }
            for (file in page.files) {
                val md5 = file.md5Checksum ?: continue
                repository.recordUploaded(md5, file.id, file.name ?: "", file.size?.toLongOrNull() ?: 0L, now)
                imported++
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return imported
    }

    /** Whether a folder lies inside [rootId]'s tree, answered from a parent map of every folder. */
    private class FolderTree(private val rootId: String, private val parentsOf: Map<String, List<String>>) {
        private val memo = HashMap<String, Boolean>()

        fun contains(folderId: String): Boolean = check(folderId, HashSet())

        private fun check(id: String, visiting: MutableSet<String>): Boolean {
            if (id == rootId) return true
            memo[id]?.let { return it }
            if (!visiting.add(id)) return false // Drive shouldn't have cycles, but never loop on one
            val result = parentsOf[id].orEmpty().any { check(it, visiting) }
            memo[id] = result
            return result
        }
    }
}
