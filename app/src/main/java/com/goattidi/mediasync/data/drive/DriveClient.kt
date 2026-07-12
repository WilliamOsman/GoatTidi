package com.goattidi.mediasync.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

sealed class SessionStatus {
    /** Server has [confirmedBytes] bytes; continue uploading from that offset. */
    data class Incomplete(val confirmedBytes: Long) : SessionStatus()
    data class Complete(val file: DriveFile) : SessionStatus()
}

sealed class ChunkResult {
    data class Continue(val confirmedBytes: Long) : ChunkResult()
    data class Done(val file: DriveFile) : ChunkResult()
}

/**
 * Thin client over the Drive v3 REST API (raw OkHttp — deliberately not the legacy
 * Google API Java client). Implements the resumable upload protocol: session start,
 * chunk PUTs with Content-Range, and the `bytes *&#47;TOTAL` status probe.
 *
 * 308 responses must reach us unmangled, so redirect following is disabled.
 */
class DriveClient(
    private val auth: DriveAuthProvider,
    httpClient: OkHttpClient,
    baseUrl: String = "https://www.googleapis.com"
) {
    // No transparent retries: a failed chunk PUT must surface so the uploader can
    // probe for the confirmed offset instead of blindly re-pushing bytes.
    private val http = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** Starts a resumable upload session and returns the session URI (persist it!). */
    suspend fun startResumableSession(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        parentFolderId: String?
    ): String {
        val metadata = buildJsonObject {
            put("name", fileName)
            put("mimeType", mimeType)
            if (parentFolderId != null) put("parents", buildJsonArray { add(parentFolderId) })
        }.toString()
        return send { token ->
            Request.Builder()
                .url("$base/upload/drive/v3/files?uploadType=resumable")
                .post(metadata.toRequestBody(JSON_TYPE))
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", sizeBytes.toString())
                .build()
        }.use { resp ->
            resp.header("Location")
                ?: throw DriveException.Http(resp.code, "Resumable session response missing Location header")
        }
    }

    /** Asks the session how many bytes it has (Content-Range: bytes *&#47;TOTAL). */
    suspend fun probeSession(sessionUri: String, totalBytes: Long): SessionStatus =
        send(sessionRequest = true) { token ->
            Request.Builder()
                .url(sessionUri)
                .put(ByteArray(0).toRequestBody(null))
                .header("Authorization", "Bearer $token")
                .header("Content-Range", "bytes */$totalBytes")
                .build()
        }.use { resp ->
            when (resp.code) {
                308 -> SessionStatus.Incomplete(confirmedBytes(resp))
                200, 201 -> SessionStatus.Complete(parseFile(resp))
                else -> throw DriveException.Http(resp.code, "Unexpected probe response ${resp.code}")
            }
        }

    suspend fun uploadChunk(sessionUri: String, data: ByteArray, offset: Long, totalBytes: Long): ChunkResult =
        send(sessionRequest = true) { token ->
            Request.Builder()
                .url(sessionUri)
                .put(data.toRequestBody(null))
                .header("Authorization", "Bearer $token")
                .header("Content-Range", "bytes $offset-${offset + data.size - 1}/$totalBytes")
                .build()
        }.use { resp ->
            when (resp.code) {
                308 -> ChunkResult.Continue(confirmedBytes(resp))
                200, 201 -> ChunkResult.Done(parseFile(resp))
                else -> throw DriveException.Http(resp.code, "Unexpected upload response ${resp.code}")
            }
        }

    /** Finds-or-creates the app's destination folder. Returns its Drive file id. */
    suspend fun ensureFolder(name: String, parentId: String? = null): String {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        var query = "name = '$escaped' and mimeType = '$FOLDER_MIME' and trashed = false"
        if (parentId != null) query += " and '$parentId' in parents"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val existing = send { token ->
            Request.Builder()
                .url("$base/drive/v3/files?q=$encoded&fields=files(id,name)&spaces=drive")
                .get()
                .header("Authorization", "Bearer $token")
                .build()
        }.use { resp ->
            json.decodeFromString(DriveFileList.serializer(), resp.body?.string().orEmpty()).files.firstOrNull()
        }
        if (existing != null) return existing.id

        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) put("parents", buildJsonArray { add(parentId) })
        }.toString()
        return send { token ->
            Request.Builder()
                .url("$base/drive/v3/files?fields=id")
                .post(metadata.toRequestBody(JSON_TYPE))
                .header("Authorization", "Bearer $token")
                .build()
        }.use { parseFile(it).id }
    }

    suspend fun getFile(fileId: String, fields: String = "id,name,md5Checksum,size,trashed"): DriveFile =
        send { token ->
            Request.Builder()
                .url("$base/drive/v3/files/$fileId?fields=$fields")
                .get()
                .header("Authorization", "Bearer $token")
                .build()
        }.use { parseFile(it) }

    private fun parseFile(resp: Response): DriveFile =
        json.decodeFromString(DriveFile.serializer(), resp.body?.string().orEmpty())

    /** Range header "bytes=0-N" means N+1 bytes are safe on the server; absent means 0. */
    private fun confirmedBytes(resp: Response): Long {
        val range = resp.header("Range") ?: return 0
        val end = range.substringAfterLast('-').toLongOrNull() ?: return 0
        return end + 1
    }

    /** Executes with auth; on 401 refreshes the token once and retries the request. */
    private suspend fun send(sessionRequest: Boolean = false, build: (String) -> Request): Response {
        var resp = executeRaw(build(auth.accessToken(false)))
        if (resp.code == 401) {
            resp.close()
            resp = executeRaw(build(auth.accessToken(forceRefresh = true)))
        }
        if (resp.isSuccessful || resp.code == 308) return resp
        val error = mapError(resp, sessionRequest)
        resp.close()
        throw error
    }

    private suspend fun executeRaw(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw DriveException.Network(e)
        }
    }

    private fun mapError(resp: Response, sessionRequest: Boolean): DriveException {
        val code = resp.code
        val bodyText = try { resp.body?.string().orEmpty() } catch (e: IOException) { "" }
        val reason = parseReason(bodyText)
        val message = "HTTP $code" + (reason?.let { " ($it)" } ?: "")
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()
        return when {
            // A dead resumable session, not a missing file
            sessionRequest && (code == 404 || code == 410) -> DriveException.SessionExpired(message)
            code == 401 -> DriveException.AuthFailed(message)
            code == 429 -> DriveException.RateLimited(retryAfter, message)
            code == 403 && reason in RATE_LIMIT_REASONS -> DriveException.RateLimited(retryAfter, message)
            code == 403 && reason == "storageQuotaExceeded" -> DriveException.StorageQuotaExceeded(message)
            code == 403 && (reason == "accessNotConfigured" || bodyText.contains("SERVICE_DISABLED")) ->
                DriveException.ServiceDisabled(message)
            code == 404 -> DriveException.NotFound(message)
            else -> DriveException.Http(code, message)
        }
    }

    private fun parseReason(body: String): String? = try {
        json.decodeFromString(DriveErrorEnvelope.serializer(), body).error?.errors?.firstOrNull()?.reason
    } catch (e: Exception) {
        null
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded")
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
