package com.goattidi.mediasync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveAuthConsentRequired
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.DriveUploader
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.repo.SyncStateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * Drains the upload queue. Stateless by design: every run derives its work from
 * the DB (invariant #4), so a kill or reboot mid-upload resumes exactly where the
 * persisted records say — stale UPLOADING rows are probed and continued, never
 * restarted blindly.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: SyncStateRepository,
    private val uploader: DriveUploader,
    private val localFiles: LocalFiles,
    private val folderResolver: DriveFolderResolver
) : CoroutineWorker(appContext, workerParams) {

    private enum class ItemResult { DONE, RETRY, ABORT }

    override suspend fun doWork(): Result {
        // Foreground promotion can fail in tests / restricted states; upload anyway
        runCatching { setForeground(getForegroundInfo()) }
        var retry = false
        for (record in repository.uploadBatch()) {
            when (processOne(record)) {
                ItemResult.DONE -> {}
                ItemResult.RETRY -> retry = true
                ItemResult.ABORT -> return Result.retry()
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    private suspend fun processOne(record: SyncRecord): ItemResult {
        val stat = localFiles.stat(record)
        if (stat == null) {
            repository.markFailed(record.mediaStoreId, "Local file no longer exists")
            return ItemResult.DONE
        }
        val prepared = ensureFreshMd5(record, stat)
        if (prepared == null) {
            repository.markFailed(record.mediaStoreId, "File kept changing while computing checksum")
            return ItemResult.DONE
        }
        val (md5, md5Stat) = prepared
        repository.markUploading(record.mediaStoreId)

        val outcome = try {
            runUpload(record, md5, md5Stat)
        } catch (e: DriveException.StorageQuotaExceeded) {
            repository.requeue(record.mediaStoreId, "Google Drive storage is full")
            return ItemResult.ABORT
        } catch (e: DriveException.AuthFailed) {
            repository.requeue(record.mediaStoreId, "Google Drive sign-in expired — reconnect in the app")
            return ItemResult.ABORT
        } catch (e: DriveAuthConsentRequired) {
            repository.requeue(record.mediaStoreId, "Google Drive authorization required")
            return ItemResult.ABORT
        } catch (e: DriveException.RateLimited) {
            repository.requeue(record.mediaStoreId)
            return ItemResult.RETRY
        } catch (e: DriveException.Network) {
            repository.requeue(record.mediaStoreId)
            return ItemResult.RETRY
        } catch (e: DriveException) {
            repository.markFailed(record.mediaStoreId, e.message ?: "Google Drive error")
            return ItemResult.DONE
        } catch (e: IOException) {
            repository.requeue(record.mediaStoreId)
            return ItemResult.RETRY
        }

        // Invariant #2: the bytes on Drive must be the bytes we hashed. If the file
        // changed underneath the upload, the result is untrustworthy — discard it.
        val after = localFiles.stat(record)
        if (after != md5Stat) {
            repository.requeueModified(record.mediaStoreId)
            return ItemResult.RETRY
        }

        when (outcome) {
            is DriveUploader.Outcome.Verified -> {
                repository.markSynced(
                    record.mediaStoreId, outcome.fileId, outcome.driveMd5, System.currentTimeMillis()
                )
                return ItemResult.DONE
            }
            is DriveUploader.Outcome.Md5Mismatch -> {
                repository.markFailed(
                    record.mediaStoreId,
                    "Checksum mismatch after upload (drive=${outcome.driveMd5}) — not marked synced",
                    driveFileId = outcome.fileId
                )
                return ItemResult.DONE
            }
            is DriveUploader.Outcome.Unverified -> {
                // Drive hasn't computed the checksum yet (large files). Stay UPLOADING
                // and retry later; verifyExisting picks it up on the next run.
                repository.markUnverified(record.mediaStoreId, outcome.fileId)
                return ItemResult.RETRY
            }
        }
    }

    private suspend fun runUpload(
        record: SyncRecord,
        md5: String,
        md5Stat: LocalFiles.Stat
    ): DriveUploader.Outcome {
        // A stale UPLOADING row with a Drive file id but no session finished uploading
        // before the process died — only the checksum verification is outstanding.
        if (record.status == SyncStatus.UPLOADING && record.driveFileId != null && record.resumeSessionUri == null) {
            try {
                return uploader.verifyExisting(record.driveFileId, md5)
            } catch (e: DriveException.NotFound) {
                // File vanished on Drive; fall through and upload it again
            }
        }
        return uploader.upload(
            DriveUploader.UploadRequest(
                fileName = record.fileName,
                mimeType = record.mimeType,
                sizeBytes = md5Stat.sizeBytes,
                localMd5 = md5,
                parentFolderId = folderResolver.resolveFolderId(),
                existingSessionUri = record.resumeSessionUri
            ),
            { offset -> localFiles.open(record, offset) },
            onSessionEstablished = { repository.saveSessionUri(record.mediaStoreId, it) },
            onProgress = { confirmed ->
                setProgress(workDataOf(KEY_MEDIA_ID to record.mediaStoreId, KEY_BYTES to confirmed))
            }
        )
    }

    /**
     * Returns a trustworthy MD5 with the stat it was computed from. Recomputes when
     * the cached one no longer matches the file's current size/date_modified, and
     * gives up (null) if the file keeps changing underneath us.
     */
    private suspend fun ensureFreshMd5(
        record: SyncRecord,
        initialStat: LocalFiles.Stat
    ): Pair<String, LocalFiles.Stat>? {
        var stat = initialStat
        if (record.localMd5 != null &&
            record.md5SizeBytes == stat.sizeBytes &&
            record.md5DateModified == stat.dateModified
        ) {
            return record.localMd5 to stat
        }
        repeat(3) {
            val md5 = Md5.of(localFiles.open(record, 0))
            val after = localFiles.stat(record) ?: return null
            if (after == stat) {
                repository.cacheMd5(record.mediaStoreId, md5, stat.sizeBytes, stat.dateModified)
                return md5 to stat
            }
            stat = after
        }
        return null
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Upload progress", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Uploading to Google Drive")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MEDIA_ID = "mediaStoreId"
        const val KEY_BYTES = "bytesConfirmed"
        const val CHANNEL_ID = "sync_progress"
        const val NOTIFICATION_ID = 42
    }
}
