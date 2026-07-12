package com.goattidi.mediasync

import android.app.Application
import android.content.ContentResolver
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.media.MediaStoreScanner
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaStoreScannerTest {

    private lateinit var contentResolver: ContentResolver

    private val columnsWithDateTaken = listOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN
    )

    private val columnsAudio = columnsWithDateTaken.dropLast(1)

    @Before
    fun setUp() {
        contentResolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
    }

    private fun stubCursor(uri: android.net.Uri, columns: List<String>, rows: Array<Array<Any>>) {
        val cursor = RoboCursor()
        cursor.setColumnNames(columns)
        cursor.setResults(rows)
        shadowOf(contentResolver).setCursor(uri, cursor)
    }

    @Test
    fun `scan populates records for images videos and audio`() {
        stubCursor(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columnsWithDateTaken,
            arrayOf(arrayOf(1L, "/dcim/Camera/a.jpg", "a.jpg", 1_000L, "image/jpeg", 1_700_000_000L, 1_700_000_000_000L))
        )
        stubCursor(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columnsWithDateTaken,
            arrayOf(arrayOf(2L, "/dcim/Camera/b.mp4", "b.mp4", 500_000_000L, "video/mp4", 1_700_000_100L, 1_700_000_100_000L))
        )
        stubCursor(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columnsAudio,
            arrayOf(arrayOf(3L, "/recordings/c.m4a", "c.m4a", 2_000L, "audio/mp4", 1_700_000_200L))
        )

        val scanned = MediaStoreScanner(contentResolver).scanAll()

        assertEquals(3, scanned.size)
        val image = scanned.single { it.mediaType == MediaType.IMAGE }
        assertEquals(1L, image.mediaStoreId)
        assertEquals("a.jpg", image.fileName)
        assertEquals(1_000L, image.sizeBytes)
        assertEquals(1_700_000_000L, image.dateModified)
        assertEquals(1_700_000_000_000L, image.dateTaken)
        assertEquals(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() + "/1",
            image.contentUri
        )

        val video = scanned.single { it.mediaType == MediaType.VIDEO }
        assertEquals("video/mp4", video.mimeType)

        val audio = scanned.single { it.mediaType == MediaType.AUDIO }
        // Audio has no DATE_TAKEN; scanner falls back to dateModified in millis
        assertEquals(1_700_000_200_000L, audio.dateTaken)
    }

    @Test
    fun `empty MediaStore yields empty scan`() {
        stubCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columnsWithDateTaken, arrayOf())
        stubCursor(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columnsWithDateTaken, arrayOf())
        stubCursor(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columnsAudio, arrayOf())

        assertEquals(0, MediaStoreScanner(contentResolver).scanAll().size)
    }
}
