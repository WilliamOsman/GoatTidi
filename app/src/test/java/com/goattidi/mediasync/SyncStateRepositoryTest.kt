package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.AppDatabase
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.media.MediaStoreScanner
import com.goattidi.mediasync.data.media.ScannedMedia
import com.goattidi.mediasync.data.repo.SyncStateRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncStateRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SyncStateRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SyncStateRepository(db.syncRecordDao(), db.uploadedContentDao(), MediaStoreScanner(context.contentResolver))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun scannedItem(
        id: Long = 1L,
        size: Long = 1_000L,
        dateModified: Long = 1_700_000_000L
    ) = ScannedMedia(
        mediaStoreId = id,
        contentUri = "content://media/external/images/media/$id",
        filePath = "/dcim/Camera/file$id.jpg",
        fileName = "file$id.jpg",
        sizeBytes = size,
        mimeType = "image/jpeg",
        mediaType = MediaType.IMAGE,
        dateTaken = dateModified * 1000,
        dateModified = dateModified
    )

    @Test
    fun `reconcile inserts new files as NOT_UPLOADED`() = runTest {
        repo.reconcile(listOf(scannedItem(id = 1), scannedItem(id = 2)))

        val all = db.syncRecordDao().getAll()
        assertEquals(2, all.size)
        assertEquals(setOf(SyncStatus.NOT_UPLOADED), all.map { it.status }.toSet())
        assertNull(all.first().localMd5)
    }

    @Test
    fun `unchanged file keeps cached MD5 and status`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)

        repo.reconcile(listOf(scannedItem())) // identical size + dateModified

        val record = db.syncRecordDao().getById(1L)!!
        assertEquals("abc123", record.localMd5)
        assertEquals(SyncStatus.NOT_UPLOADED, record.status)
    }

    @Test
    fun `date_modified change invalidates cached MD5`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)

        repo.reconcile(listOf(scannedItem(dateModified = 1_700_000_999L)))

        val record = db.syncRecordDao().getById(1L)!!
        assertNull(record.localMd5)
        assertNull(record.md5SizeBytes)
        assertNull(record.md5DateModified)
    }

    @Test
    fun `size change invalidates cached MD5`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)

        repo.reconcile(listOf(scannedItem(size = 2_000L)))

        assertNull(db.syncRecordDao().getById(1L)!!.localMd5)
    }

    @Test
    fun `modified SYNCED file becomes MODIFIED_SINCE_UPLOAD`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)
        val dao = db.syncRecordDao()
        dao.upsert(
            dao.getById(1L)!!.copy(
                status = SyncStatus.SYNCED,
                driveFileId = "drive-1",
                driveMd5 = "abc123",
                uploadedAt = 1_700_000_500_000L
            )
        )

        repo.reconcile(listOf(scannedItem(dateModified = 1_700_000_999L)))

        val record = dao.getById(1L)!!
        assertEquals(SyncStatus.MODIFIED_SINCE_UPLOAD, record.status)
        assertNull(record.localMd5)
        // Drive-side facts are preserved — the upload really happened
        assertEquals("drive-1", record.driveFileId)
        assertNotNull(record.driveMd5)
    }

    @Test
    fun `modified but not-yet-synced file keeps its status`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)

        repo.reconcile(listOf(scannedItem(dateModified = 1_700_000_999L)))

        val record = db.syncRecordDao().getById(1L)!!
        assertEquals(SyncStatus.NOT_UPLOADED, record.status)
        assertNull(record.localMd5)
    }

    @Test
    fun `record disappears when file is deleted from device`() = runTest {
        repo.reconcile(listOf(scannedItem(id = 1), scannedItem(id = 2)))

        repo.reconcile(listOf(scannedItem(id = 2))) // file 1 was deleted locally

        val all = db.syncRecordDao().getAll()
        assertEquals(listOf(2L), all.map { it.mediaStoreId })
    }

    @Test
    fun `md5IsCurrent reflects snapshot fields`() = runTest {
        repo.reconcile(listOf(scannedItem()))
        repo.cacheMd5(1L, "abc123", sizeBytes = 1_000L, dateModified = 1_700_000_000L)
        val dao = db.syncRecordDao()

        assertEquals(true, dao.getById(1L)!!.md5IsCurrent)

        repo.reconcile(listOf(scannedItem(size = 2_000L)))
        assertEquals(false, dao.getById(1L)!!.md5IsCurrent)
    }
}
