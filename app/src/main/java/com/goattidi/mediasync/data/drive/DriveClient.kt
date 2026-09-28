package com.goattidi.mediasync.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException

/**
 * Streams a chunk while reporting absolute file position as bytes hit the socket —
 * this is what makes upload progress move continuously instead of per-chunk.
 */
private class ProgressRequestBody(
    private val data: ByteArray,
    private val baseOffset: Long,
    private val onBytesSent: ((Long) -> Unit)?
) : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        if (onBytesSent == null) {
            sink.write(data)
            return
        }
        var written = 0
        while (written < data.size) {
            val n = minOf(REPORT_STEP, data.size - written)
            sink.write(data, written, n)
            written += n
            onBytesSent.invoke(baseOffset + written)
        }
    }

    private companion object {
        const val REPORT_STEP = 256 * 1024
    }
}

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

    suspend fun uploadChunk(
        sessionUri: String,
        data: ByteArray,
        offset: Long,
        totalBytes: Long,
        onBytesSent: ((Long) -> Unit)? = null
    ): ChunkResult =
        send(sessionRequest = true) { token ->
            Request.Builder()
                .url(sessionUri)
                .put(ProgressRequestBody(data, offset, onBytesSent))
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

    /**
     * Finds any folder by name (optionally under a parent) — including the user's own
     * folders, since drive.readonly is granted for the duplicate index. Never creates.
     */
    suspend fun findFolder(name: String, parentId: String? = null): String? =
        searchFolders(name, parentId).firstOrNull()?.id

    /**
     * Like [findFolder], but only matches folders this app created. Destination lookups
     * must use this: a same-named folder of the user's is visible via drive.readonly, but
     * drive.file gives no write access to it, so every upload into it would fail.
     */
    suspend fun findOwnFolder(name: String, parentId: String? = null): String? =
        searchFolders(name, parentId).firstOrNull { it.isAppAuthorized }?.id

    private suspend fun searchFolders(name: String, parentId: String?): List<DriveFile> {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        var query = "name = '$escaped' and mimeType = '$DRIVE_FOLDER_MIME' and trashed = false"
        if (parentId != null) query += " and '$parentId' in parents"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return send { token ->
            Request.Builder()
                .url("$base/drive/v3/files?q=$encoded&fields=files(id,name,isAppAuthorized)&pageSize=1000&spaces=drive")
                .get()
                .header("Authorization", "Bearer $token")
                .build()
        }.use { resp ->
            json.decodeFromString(DriveFileList.serializer(), resp.body?.string().orEmpty()).files
        }
    }

    /** Resolves a slash-separated folder path ("Media/Videos") to a folder id. */
    suspend fun resolveFolderPath(path: String): String? {
        var parent: String? = null
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        for (segment in segments) {
            parent = findFolder(segment, parent) ?: return null
        }
        return parent
    }

    /**
     * Finds-or-creates the app's destination folder. Returns its Drive file id. Only ever
     * reuses a folder this app created — never a same-named folder of the user's.
     */
    suspend fun ensureFolder(name: String, parentId: String? = null): String {
        findOwnFolder(name, parentId)?.let { return it }
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", DRIVE_FOLDER_MIME)
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

    /** Lists all subfolders of a folder, sorted by name ("root" = My Drive). */
    suspend fun listFolders(parentId: String = "root"): List<DriveFile> {
        val results = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val q = java.net.URLEncoder.encode(
                "'$parentId' in parents and mimeType = '$DRIVE_FOLDER_MIME' and trashed = false", "UTF-8"
            )
            var url = "$base/drive/v3/files?q=$q&fields=nextPageToken,files(id,name)" +
                "&orderBy=name&pageSize=1000&spaces=drive"
            if (pageToken != null) url += "&pageToken=$pageToken"
            val page = send { token ->
                Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
            }.use { resp ->
                json.decodeFromString(DriveFileList.serializer(), resp.body?.string().orEmpty())
            }
            results += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return results
    }

    /** Lists the direct children of a folder (files and subfolders). */
    suspend fun listChildren(folderId: String, pageToken: String? = null, pageSize: Int = 1000): DriveFileList {
        val q = java.net.URLEncoder.encode("'$folderId' in parents and trashed = false", "UTF-8")
        var url = "$base/drive/v3/files?q=$q" +
            "&fields=nextPageToken,files(id,name,md5Checksum,size,mimeType)&pageSize=$pageSize&spaces=drive"
        if (pageToken != null) url += "&pageToken=$pageToken"
        return send { token ->
            Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
        }.use { resp ->
            json.decodeFromString(DriveFileList.serializer(), resp.body?.string().orEmpty())
        }
    }

    /**
     * Pages through the non-folder files this app created — wherever the user has since
     * moved them within Drive. Under drive.file alone that's everything visible; once the
     * user opts into drive.readonly the whole Drive is visible, so the app's own files are
     * picked out by isAppAuthorized (Drive can't filter on it server-side).
     */
    suspend fun listFiles(pageToken: String? = null, pageSize: Int = 1000): DriveFileList {
        val q = java.net.URLEncoder.encode("trashed = false and mimeType != '$DRIVE_FOLDER_MIME'", "UTF-8")
        var url = "$base/drive/v3/files?q=$q" +
            "&fields=nextPageToken,files(id,name,md5Checksum,size,isAppAuthorized)&pageSize=$pageSize&spaces=drive"
        if (pageToken != null) url += "&pageToken=$pageToken"
        return send { token ->
            Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()
        }.use { resp ->
            val page = json.decodeFromString(DriveFileList.serializer(), resp.body?.string().orEmpty())
            page.copy(files = page.files.filter { it.isAppAuthorized })
        }
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
    }
}
