package com.goattidi.mediasync.data.drive

import java.io.IOException
import java.io.InputStream

/**
 * Uploads one file through the resumable protocol and verifies the result.
 *
 * Invariant #1: the outcome is [Outcome.Verified] ONLY when Drive returned an
 * md5Checksum equal to the locally computed MD5. A 2xx without a matching
 * checksum yields [Outcome.Unverified] or [Outcome.Md5Mismatch] — never Verified.
 *
 * Resumption: with an existing session URI the uploader never blindly re-pushes;
 * it probes for the confirmed offset first. A 404/410 on the session URI means
 * the session expired — it is discarded and the upload restarts fresh.
 */
class DriveUploader(
    private val client: DriveClient,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE
) {
    init {
        require(chunkSizeBytes > 0 && chunkSizeBytes % CHUNK_GRANULARITY == 0) {
            "Chunk size must be a positive multiple of 256 KiB"
        }
    }

    /** Opens the local file positioned at [offset]. Called again after reconnects. */
    fun interface Source {
        fun open(offset: Long): InputStream
    }

    data class UploadRequest(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val localMd5: String,
        val parentFolderId: String? = null,
        val existingSessionUri: String? = null
    )

    sealed class Outcome {
        abstract val fileId: String

        data class Verified(override val fileId: String, val driveMd5: String) : Outcome()
        data class Md5Mismatch(override val fileId: String, val driveMd5: String) : Outcome()

        /** Bytes accepted but Drive hasn't produced a checksum yet — NOT synced. */
        data class Unverified(override val fileId: String) : Outcome()
    }

    suspend fun upload(
        request: UploadRequest,
        source: Source,
        onSessionEstablished: suspend (String) -> Unit = {},
        onProgress: suspend (confirmedBytes: Long) -> Unit = {},
        /** Fires from the network thread as bytes hit the socket (display only). */
        onBytesSent: ((Long) -> Unit)? = null
    ): Outcome {
        require(request.sizeBytes > 0) { "Cannot upload empty file ${request.fileName}" }
        var sessionUri = request.existingSessionUri
        var offset = 0L
        var completed: DriveFile? = null

        val existingUri = sessionUri
        if (existingUri != null) {
            try {
                when (val status = withRetry(retryPolicy) { client.probeSession(existingUri, request.sizeBytes) }) {
                    is SessionStatus.Complete -> completed = status.file
                    is SessionStatus.Incomplete -> offset = status.confirmedBytes
                }
            } catch (e: DriveException.SessionExpired) {
                sessionUri = null
            }
        }

        var restartsLeft = 1
        while (completed == null) {
            val uri = sessionUri ?: withRetry(retryPolicy) {
                client.startResumableSession(
                    request.fileName, request.mimeType, request.sizeBytes, request.parentFolderId
                )
            }.also {
                sessionUri = it
                offset = 0
                onSessionEstablished(it)
            }
            completed = try {
                pushBytes(uri, request, source, offset, onProgress, onBytesSent)
            } catch (e: DriveException.SessionExpired) {
                if (restartsLeft-- <= 0) throw e
                sessionUri = null
                null
            }
        }
        return verify(completed, request.localMd5)
    }

    /** Re-checks a previously completed upload whose checksum wasn't available yet. */
    suspend fun verifyExisting(fileId: String, localMd5: String): Outcome =
        verify(withRetry(retryPolicy) { client.getFile(fileId) }, localMd5)

    private suspend fun pushBytes(
        sessionUri: String,
        request: UploadRequest,
        source: Source,
        startOffset: Long,
        onProgress: suspend (Long) -> Unit,
        onBytesSent: ((Long) -> Unit)? = null
    ): DriveFile {
        var offset = startOffset
        var stream: InputStream? = null
        var networkFailures = 0
        try {
            while (true) {
                val input = stream ?: source.open(offset).also { stream = it }
                val chunkLen = minOf(chunkSizeBytes.toLong(), request.sizeBytes - offset).toInt()
                val data = ByteArray(chunkLen).also { readFully(input, it) }

                val result = try {
                    putChunk(sessionUri, data, offset, request.sizeBytes, onBytesSent)
                } catch (e: DriveException.Network) {
                    networkFailures++
                    if (networkFailures >= retryPolicy.maxAttempts) throw e
                    retryPolicy.sleeper(retryPolicy.backoffMs(networkFailures))
                    // Bytes may have partially landed — never re-push blindly, probe first
                    closeQuietly(stream); stream = null
                    when (val st = withRetry(retryPolicy) { client.probeSession(sessionUri, request.sizeBytes) }) {
                        is SessionStatus.Complete -> return st.file
                        is SessionStatus.Incomplete -> {
                            offset = st.confirmedBytes
                            onProgress(offset)
                        }
                    }
                    continue
                }

                when (result) {
                    is ChunkResult.Done -> return result.file
                    is ChunkResult.Continue -> {
                        val confirmed = result.confirmedBytes
                        if (confirmed != offset + chunkLen) {
                            // Server kept less than we sent; realign the stream
                            closeQuietly(stream); stream = null
                        }
                        offset = confirmed
                        onProgress(confirmed)
                    }
                }
            }
        } finally {
            closeQuietly(stream)
        }
    }

    private suspend fun putChunk(
        sessionUri: String,
        data: ByteArray,
        offset: Long,
        total: Long,
        onBytesSent: ((Long) -> Unit)? = null
    ): ChunkResult {
        var attempt = 0
        while (true) {
            try {
                return client.uploadChunk(sessionUri, data, offset, total, onBytesSent)
            } catch (e: DriveException.RateLimited) {
                attempt++
                if (attempt >= retryPolicy.maxAttempts) throw e
                retryPolicy.sleeper(e.retryAfterSeconds?.times(1000) ?: retryPolicy.backoffMs(attempt))
            }
        }
    }

    private suspend fun verify(file: DriveFile, localMd5: String): Outcome {
        val driveMd5 = file.md5Checksum
            ?: withRetry(retryPolicy) { client.getFile(file.id) }.md5Checksum
            ?: return Outcome.Unverified(file.id)
        return if (driveMd5.equals(localMd5, ignoreCase = true)) {
            Outcome.Verified(file.id, driveMd5)
        } else {
            Outcome.Md5Mismatch(file.id, driveMd5)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw IOException("Unexpected EOF at $read/${target.size} reading local file")
            read += n
        }
    }

    private fun closeQuietly(stream: InputStream?) {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val CHUNK_GRANULARITY = 256 * 1024
        const val DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024
    }
}
