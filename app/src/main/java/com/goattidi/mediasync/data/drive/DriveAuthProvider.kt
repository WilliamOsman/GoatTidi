package com.goattidi.mediasync.data.drive

import android.app.PendingIntent

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Read-only access to all of Drive. Requested ONLY so the duplicate index can scan
 * the single user-designated folder tree (Settings → duplicate-check folder) for
 * files uploaded by other tools; the app never reads outside that tree and never
 * writes outside the folders it created. Google has no narrower per-folder scope.
 */
const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/**
 * Supplies OAuth access tokens for the Drive REST API. The production
 * implementation uses Credential Manager / AuthorizationClient; tests use fakes.
 */
interface DriveAuthProvider {
    /**
     * Returns a valid access token. With [forceRefresh] the cached token must be
     * discarded first (used after a 401).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String
}

/**
 * Thrown when Google requires interactive user consent before tokens can be issued.
 * The UI must launch [pendingIntent] and retry afterwards; the queue stays intact.
 */
class DriveAuthConsentRequired(val pendingIntent: PendingIntent?) :
    Exception("User consent required for Drive access")
