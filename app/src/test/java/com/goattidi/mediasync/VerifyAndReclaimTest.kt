package com.goattidi.mediasync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.AppDatabase
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.hash.Md5
import com.goattidi.mediasync.data.media.MediaStoreScanner
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.sync.LocalFiles
import com.goattidi.mediasync.sync.ReclaimEngine
import com.goattidi.mediasync.sync.VerifyEngine
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VerifyAndReclaimTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SyncStateRepository
    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient

    private val content = "hello world".toByteArray()
    private val contentMd5 = "5eb63bbbe01eeed093cb22bb8f5acdc3"
    private val stat = LocalFiles.Stat(11, 1_700_000_000L)
    private val policy = RetryPolicy(maxAttempts = 2, sleeper = {})

    private class StaticLocalFiles(
        private val content: ByteArray?,
        private val stat: LocalFiles.Stat?
    ) : LocalFiles {
        override fun stat(record: SyncRecord) = if (content == null) null else stat
        override fun open(record: SyncRecord, offset: Long): InputStream =
            ByteArrayInputStream(content!!, offset.toInt(), content.size - offset.toInt())
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SyncStateRepository(db.syncRecordDao(), db.uploadedContentDao(), MediaStoreScanner(context.contentResolver))
        server = MockWebServer()
        server.start()
        client = DriveClient(FakeDriveAuth(), OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private suspend fun seed(
        id: Long,
        status: SyncStatus,
        driveFileId: String? = "drv-$id",
        localMd5: String? = contentMd5,
        driveMd5: String? = contentMd5
    ): SyncRecord {
        val rec = SyncRecord(
            mediaStoreId = id,
            localUri = "content://media/external/video/media/$id",
            filePath = "/dcim/clip$id.mp4",
            fileName = "clip$id.mp4",
            sizeBytes = stat.sizeBytes,
            mimeType = "video/mp4",
            mediaType = MediaType.VIDEO,
            dateTaken = 0,
            dateModified = stat.dateModified,
            localMd5 = localMd5,
            md5SizeBytes = if (localMd5 != null) stat.sizeBytes else null,
            md5DateModified = if (localMd5 != null) stat.dateModified else null,
            driveFileId = driveFileId,
            driveMd5 = driveMd5,
            uploadedAt = 1_700_000_100_000L,
            status = status,
            failureReason = null,
            resumeSessionUri = null
        )
        db.syncRecordDao().upsert(rec)
        return rec
    }

    // ---- VerifyEngine ----

    @Test
    fun `verify flags orphan when Drive file is 404`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(MockResponse().setResponseCode(404))

        val summary = VerifyEngine(repo, client).verifyAll(policy)

        assertEquals(1, summary.orphaned)
        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify flags orphan when Drive file is trashed`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5","trashed":true}""")
        )

        VerifyEngine(repo, client).verifyAll(policy)

        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify promotes unverified upload once checksum matches`() = runTest {
        seed(1, SyncStatus.UPLOADING, driveMd5 = null)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )

        val summary = VerifyEngine(repo, client).verifyAll(policy)

        assertEquals(1, summary.confirmedSynced)
        val rec = db.syncRecordDao().getById(1)!!
        assertEquals(SyncStatus.SYNCED, rec.status)
        assertEquals(contentMd5, rec.driveMd5)
    }

    @Test
    fun `verify demotes SYNCED file whose checksums no longer match`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"ffffffffffffffffffffffffffffffff"}""")
        )

        val summary = VerifyEngine(repo, client).verifyAll(policy)

        assertEquals(1, summary.modified)
        assertEquals(SyncStatus.MODIFIED_SINCE_UPLOAD, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `verify never promotes without a Drive checksum`() = runTest {
        seed(1, SyncStatus.UPLOADING, driveMd5 = null)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-1"}"""))

        val summary = VerifyEngine(repo, client).verifyAll(policy)

        assertEquals(1, summary.stillUnverified)
        assertEquals(SyncStatus.UPLOADING, db.syncRecordDao().getById(1)!!.status)
    }

    // ---- ReclaimEngine (invariant #3) ----

    @Test
    fun `delete allowed when fresh local md5 matches fresh Drive md5`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )
        val engine = ReclaimEngine(repo, client, StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertEquals(ReclaimEngine.Gate.Safe, gate)
    }

    @Test
    fun `delete blocked when local bytes changed even though status says SYNCED`() = runTest {
        seed(1, SyncStatus.SYNCED) // DB still claims SYNCED with matching md5s
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":"drv-1","md5Checksum":"$contentMd5"}""")
        )
        // ...but the actual bytes on disk are different now
        val engine = ReclaimEngine(repo, client, StaticLocalFiles("tampered!!".toByteArray(), stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(SyncStatus.MODIFIED_SINCE_UPLOAD, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `delete blocked when Drive copy is gone`() = runTest {
        seed(1, SyncStatus.SYNCED)
        server.enqueue(MockResponse().setResponseCode(404))
        val engine = ReclaimEngine(repo, client, StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(SyncStatus.ORPHANED, db.syncRecordDao().getById(1)!!.status)
    }

    @Test
    fun `delete blocked for non-synced statuses without any network call`() = runTest {
        seed(1, SyncStatus.MODIFIED_SINCE_UPLOAD)
        val engine = ReclaimEngine(repo, client, StaticLocalFiles(content, stat))

        val gate = engine.confirmSafeToDelete(1, policy)

        assertTrue(gate is ReclaimEngine.Gate.Blocked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `candidates are synced files sorted biggest first`() = runTest {
        seed(1, SyncStatus.SYNCED)
        db.syncRecordDao().upsert(seed(2, SyncStatus.SYNCED).copy(sizeBytes = 999_999))
        seed(3, SyncStatus.NOT_UPLOADED)
        val engine = ReclaimEngine(repo, client, StaticLocalFiles(content, stat))

        val candidates = engine.candidates()

        assertEquals(listOf(2L, 1L), candidates.map { it.mediaStoreId })
    }

    // ---- ensureFolder ----

    @Test
    fun `ensureFolder returns existing folder id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"files":[{"id":"folder-existing","name":"Phone Media"}]}""")
        )

        val id = client.ensureFolder("Phone Media")

        assertEquals("folder-existing", id)
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("q="))
    }

    @Test
    fun `ensureFolder creates folder when absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"folder-new"}"""))

        val id = client.ensureFolder("Phone Media")

        assertEquals("folder-new", id)
        server.takeRequest() // list
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertTrue(create.body.readUtf8().contains("application/vnd.google-apps.folder"))
    }
}
