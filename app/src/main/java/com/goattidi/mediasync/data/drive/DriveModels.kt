package com.goattidi.mediasync.data.drive

import kotlinx.serialization.Serializable
import java.io.IOException

const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"

@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    val md5Checksum: String? = null,
    val size: String? = null,
    val mimeType: String? = null,
    val trashed: Boolean = false
) {
    val isFolder: Boolean get() = mimeType == DRIVE_FOLDER_MIME
}

@Serializable
data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null
)

@Serializable
data class DriveErrorEnvelope(val error: DriveErrorBody? = null)

@Serializable
data class DriveErrorBody(
    val code: Int = 0,
    val message: String = "",
    val errors: List<DriveErrorDetail> = emptyList(),
    val status: String? = null
)

@Serializable
data class DriveErrorDetail(
    val reason: String? = null,
    val message: String? = null,
    val domain: String? = null
)

/**
 * Typed Drive failures. Callers decide retry semantics per type: rate limits and
 * network errors are retryable with backoff; quota, auth, and session expiry are not.
 */
sealed class DriveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RateLimited(val retryAfterSeconds: Long?, message: String) : DriveException(message)
    class StorageQuotaExceeded(message: String) : DriveException(message)
    class AuthFailed(message: String) : DriveException(message)
    class SessionExpired(message: String) : DriveException(message)
    class NotFound(message: String) : DriveException(message)
    class ServiceDisabled(message: String) : DriveException(message)
    class Http(val code: Int, message: String) : DriveException(message)
    class Network(cause: IOException) : DriveException("Network error: ${cause.message}", cause)
}
