package com.goattidi.mediasync

import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.DriveUploader
import com.goattidi.mediasync.data.drive.RetryPolicy
import com.goattidi.mediasync.data.hash.Md5
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

class DriveUploaderTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: FakeDriveAuth
    private lateinit var client: DriveClient
    private val sleeps = mutableListOf<Long>()
    private lateinit var policy: RetryPolicy

    private val chunk = DriveUploader.CHUNK_GRANULARITY // 256 KiB test chunk size

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth = FakeDriveAuth()
        client = DriveClient(auth, OkHttpClient(), server.url("/").toString())
        sleeps.clear()
        policy = RetryPolicy(maxAttempts = 4, sleeper = { sleeps += it })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uploader() = DriveUploader(client, policy, chunkSizeBytes = chunk)

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    private fun source(content: ByteArray) = DriveUploader.Source { offset ->
        ByteArrayInputStream(content, offset.toInt(), content.size - offset.toInt())
    }

    private fun request(content: ByteArray, sessionUri: String? = null) = DriveUploader.UploadRequest(
        fileName = "video.mp4",
        mimeType = "video/mp4",
        sizeBytes = content.size.toLong(),
        localMd5 = Md5.of(ByteArrayInputStream(content)),
        existingSessionUri = sessionUri
    )

    private fun sessionStartResponse(path: String = "/upload/sess-1") =
        MockResponse().setResponseCode(200).setHeader("Location", server.url(path).toString())

    private fun doneResponse(md5: String, id: String = "drive-file-1") =
        MockResponse().setResponseCode(200).setBody("""{"id":"$id","md5Checksum":"$md5"}""")

    @Test
    fun `happy path uploads chunks and verifies md5`() = runTest {
        val content = bytes(chunk + 100) // two chunks
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${chunk - 1}"))
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        assertEquals("drive-file-1", outcome.fileId)

        server.takeRequest() // session start
        val put1 = server.takeRequest()
        assertEquals("bytes 0-${chunk - 1}/${content.size}", put1.getHeader("Content-Range"))
        val put2 = server.takeRequest()
        assertEquals("bytes $chunk-${content.size - 1}/${content.size}", put2.getHeader("Content-Range"))
        assertEquals(100, put2.bodySize)
    }

    @Test
    fun `existing session resumes from probed offset instead of re-pushing`() = runTest {
        val content = bytes(chunk + 500)
        val sessionUri = server.url("/upload/resume-me").toString()
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${chunk - 1}"))
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))

        val outcome = uploader().upload(request(content, sessionUri), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        val probe = server.takeRequest()
        assertEquals("bytes */${content.size}", probe.getHeader("Content-Range"))
        assertEquals(0, probe.bodySize)
        val put = server.takeRequest()
        assertEquals("bytes $chunk-${content.size - 1}/${content.size}", put.getHeader("Content-Range"))
        assertEquals(500, put.bodySize)
    }

    @Test
    fun `disconnect mid-body probes for offset then resumes`() = runTest {
        val content = bytes(2 * chunk)
        val md5 = Md5.of(ByteArrayInputStream(content))
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${chunk / 2 - 1}"))
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${chunk / 2 + chunk - 1}"))
        server.enqueue(doneResponse(md5))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        server.takeRequest() // start
        server.takeRequest() // killed PUT
        val probe = server.takeRequest()
        assertEquals("bytes */${content.size}", probe.getHeader("Content-Range"))
        // resumes exactly at the server-confirmed offset, not the start
        val resumed = server.takeRequest()
        assertEquals("bytes ${chunk / 2}-${chunk / 2 + chunk - 1}/${content.size}", resumed.getHeader("Content-Range"))
        assertTrue(sleeps.isNotEmpty()) // backed off before probing
    }

    @Test
    fun `rate limit 403 backs off and retries same chunk`() = runTest {
        val content = bytes(1000)
        server.enqueue(sessionStartResponse())
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"errors":[{"reason":"userRateLimitExceeded"}]}}"""
            )
        )
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        assertEquals(1, sleeps.size)
    }

    @Test
    fun `429 honors Retry-After header`() = runTest {
        val content = bytes(1000)
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))

        uploader().upload(request(content), source(content))

        assertEquals(listOf(7_000L), sleeps)
    }

    @Test
    fun `storage quota error propagates immediately without retry`() = runTest {
        val content = bytes(1000)
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}]}}"""
            )
        )
        try {
            uploader().upload(request(content), source(content))
            fail("expected StorageQuotaExceeded")
        } catch (e: DriveException.StorageQuotaExceeded) {
            assertTrue(sleeps.isEmpty())
        }
    }

    @Test
    fun `expired session restarts upload from scratch`() = runTest {
        val content = bytes(1000)
        val deadSession = server.url("/upload/dead").toString()
        server.enqueue(MockResponse().setResponseCode(410)) // probe: session gone
        server.enqueue(sessionStartResponse("/upload/fresh"))
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))

        val outcome = uploader().upload(request(content, deadSession), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        server.takeRequest() // failed probe
        val start = server.takeRequest()
        assertEquals("POST", start.method) // new session was created
        val put = server.takeRequest()
        assertEquals("bytes 0-999/1000", put.getHeader("Content-Range"))
    }

    @Test
    fun `onBytesSent reports monotonically up to the full file size`() = runTest {
        // 512 KiB chunks with 256 KiB report steps → multiple reports per chunk
        val chunkSize = 2 * chunk
        val content = bytes(chunkSize + 175_000)
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-${chunkSize - 1}"))
        server.enqueue(doneResponse(Md5.of(ByteArrayInputStream(content))))
        val reports = mutableListOf<Long>()

        DriveUploader(client, policy, chunkSizeBytes = chunkSize)
            .upload(request(content), source(content), onBytesSent = { reports += it })

        assertTrue(reports.size > 2) // finer than once-per-chunk
        assertEquals(content.size.toLong(), reports.max())
        assertEquals(reports.sorted(), reports) // monotonic
    }

    @Test
    fun `md5 mismatch is never Verified`() = runTest {
        val content = bytes(1000)
        server.enqueue(sessionStartResponse())
        server.enqueue(doneResponse("00000000000000000000000000000000"))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Md5Mismatch)
    }

    @Test
    fun `missing checksum in upload response falls back to files get`() = runTest {
        val content = bytes(1000)
        val md5 = Md5.of(ByteArrayInputStream(content))
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f9"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f9","md5Checksum":"$md5"}"""))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Verified)
        server.takeRequest() // start
        server.takeRequest() // put
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertTrue(get.path!!.contains("/drive/v3/files/f9"))
    }

    @Test
    fun `checksum unavailable everywhere yields Unverified not Verified`() = runTest {
        val content = bytes(1000)
        server.enqueue(sessionStartResponse())
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f9"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f9"}"""))

        val outcome = uploader().upload(request(content), source(content))

        assertTrue(outcome is DriveUploader.Outcome.Unverified)
    }
}
