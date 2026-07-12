package com.goattidi.mediasync.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.goattidi.mediasync.data.db.MediaType
import javax.inject.Inject

/** A media file as reported by MediaStore during a scan. */
data class ScannedMedia(
    val mediaStoreId: Long,
    val contentUri: String,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val mediaType: MediaType,
    val dateTaken: Long,
    /** MediaStore DATE_MODIFIED, in seconds since epoch. */
    val dateModified: Long
)

/**
 * Reads photos, videos, and audio recordings from MediaStore. Pure read layer —
 * persistence and status decisions belong to the repository.
 */
class MediaStoreScanner @Inject constructor(
    private val contentResolver: ContentResolver
) {

    fun scanAll(): List<ScannedMedia> =
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, hasDateTaken = true) +
        scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, hasDateTaken = true) +
        scan(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaType.AUDIO, hasDateTaken = false)

    private fun scan(collection: Uri, mediaType: MediaType, hasDateTaken: Boolean): List<ScannedMedia> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (hasDateTaken) add(MediaStore.MediaColumns.DATE_TAKEN)
        }.toTypedArray()

        val results = mutableListOf<ScannedMedia>()
        contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenCol = if (hasDateTaken) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val dateModified = cursor.getLong(modifiedCol)
                val dateTaken = if (takenCol >= 0) cursor.getLong(takenCol) else 0L
                results += ScannedMedia(
                    mediaStoreId = id,
                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    filePath = cursor.getString(dataCol) ?: "",
                    fileName = cursor.getString(nameCol) ?: "",
                    sizeBytes = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "",
                    mediaType = mediaType,
                    dateTaken = if (dateTaken > 0) dateTaken else dateModified * 1000,
                    dateModified = dateModified
                )
            }
        }
        return results
    }
}
