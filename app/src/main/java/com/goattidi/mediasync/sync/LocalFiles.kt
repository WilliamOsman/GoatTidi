package com.goattidi.mediasync.sync

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.goattidi.mediasync.data.db.SyncRecord
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Access to the bytes and identity (size + date_modified) of a local media file.
 * Abstracted so the sync engine can be tested without a device.
 */
interface LocalFiles {
    data class Stat(val sizeBytes: Long, val dateModified: Long)

    /** Current size and date_modified, or null if the file no longer exists. */
    fun stat(record: SyncRecord): Stat?

    /** Opens the file positioned at [offset]. */
    fun open(record: SyncRecord, offset: Long): InputStream
}

class MediaStoreLocalFiles @Inject constructor(
    private val contentResolver: ContentResolver
) : LocalFiles {

    override fun stat(record: SyncRecord): LocalFiles.Stat? {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        contentResolver.query(Uri.parse(record.localUri), projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return LocalFiles.Stat(cursor.getLong(0), cursor.getLong(1))
            }
        }
        return null
    }

    override fun open(record: SyncRecord, offset: Long): InputStream {
        val stream = contentResolver.openInputStream(Uri.parse(record.localUri))
            ?: throw IOException("Cannot open ${record.localUri}")
        var toSkip = offset
        while (toSkip > 0) {
            val skipped = stream.skip(toSkip)
            if (skipped <= 0) {
                stream.close()
                throw IOException("Could not seek to offset $offset in ${record.localUri}")
            }
            toSkip -= skipped
        }
        return stream
    }
}
