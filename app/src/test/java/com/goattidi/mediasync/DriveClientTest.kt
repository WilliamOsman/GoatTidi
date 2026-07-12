package com.goattidi.mediasync

import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.SessionStatus
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DriveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: FakeDriveAuth
    private lateinit var client: DriveClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth = FakeDriveAuth()
        client = DriveClient(auth, OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `start session returns Location and sends upload headers`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Location", server.url("/upload/sess-1").toString())
        )

        val uri = client.startResumableSession("a.jpg", "image/jpeg", 1234, parentFolderId = "folder9")

        assertEquals(server.url("/upload/sess-1").toString(), uri)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("Bearer token-initial", req.getHeader("Authorization"))
        assertEquals("image/jpeg", req.getHeader("X-Upload-Content-Type"))
        assertEquals("1234", req.getHeader("X-Upload-Content-Length"))
        assertTrue(req.body.readUtf8().contains("\"parents\":[\"folder9\"]"))
    }

    @Test
    fun `401 triggers one token refresh and retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Location", server.url("/upload/sess-2").toString())
        )

        client.startResumableSession("a.jpg", "image/jpeg", 10, null)

        assertEquals(1, auth.refreshes)
        server.takeRequest()
        assertEquals("Bearer token-refreshed-1", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `probe parses confirmed byte offset from Range header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(308).setHeader("Range", "bytes=0-262143"))

        val status = client.probeSession(server.url("/upload/sess").toString(), 1_000_000)

        assertEquals(SessionStatus.Incomplete(262_144L), status)
        val req = server.takeRequest()
        assertEquals("bytes */1000000", req.getHeader("Content-Range"))
    }

    @Test
    fun `probe without Range header means zero bytes confirmed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(308))

        val status = client.probeSession(server.url("/upload/sess").toString(), 100)

        assertEquals(SessionStatus.Incomplete(0L), status)
    }

    @Test
    fun `404 on session URI maps to SessionExpired`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client.probeSession(server.url("/upload/dead-sess").toString(), 100)
            fail("expected SessionExpired")
        } catch (e: DriveException.SessionExpired) {
            // expected
        }
    }

    @Test
    fun `403 storageQuotaExceeded maps to StorageQuotaExceeded`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":403,"message":"quota","errors":[{"reason":"storageQuotaExceeded"}]}}"""
            )
        )
        try {
            client.startResumableSession("a.jpg", "image/jpeg", 10, null)
            fail("expected StorageQuotaExceeded")
        } catch (e: DriveException.StorageQuotaExceeded) {
            // expected
        }
    }

    @Test
    fun `404 on files get maps to NotFound not SessionExpired`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client.getFile("gone")
            fail("expected NotFound")
        } catch (e: DriveException.NotFound) {
            // expected
        }
    }
}
