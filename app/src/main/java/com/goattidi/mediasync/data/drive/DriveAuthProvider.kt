package com.goattidi.mediasync.data.drive

import android.app.PendingIntent

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Read-only access to all of Drive. Opt-in, requested ONLY when the user sets up a
 * duplicate-check folder (Settings → Choose Drive folder…) so the duplicate index can
 * scan that one tree for files uploaded by other tools; the app never reads outside
 * that tree and never writes outside the folders it created. Google has no narrower
 * per-folder scope. Without it, drive.file still covers every file the app uploaded,
 * wherever the user has since moved it.
 */
const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/**
 * Supplies OAuth access tokens for the Drive REST API. The production
 * implementation uses Credential Manager / AuthorizationClient; tests use fakes.
 */
interface DriveAuthProvider {
    /**
     * Returns a valid access token: drive.file, plus drive.readonly only while the user
     * has opted into external duplicate detection. With [forceRefresh] the cached token
     * must be discarded first (used after a 401).
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String

    /**
     * Authorizes drive.readonly on top of drive.file, ahead of the user opting in.
     * Throws [DriveAuthConsentRequired] when Google must ask; call again after consent.
     */
    suspend fun authorizeExternalRead()
}

/**
 * Thrown when Google requires interactive user consent before tokens can be issued.
 * The UI must launch [pendingIntent] and retry afterwards; the queue stays intact.
 */
class DriveAuthConsentRequired(val pendingIntent: PendingIntent?) :
    Exception("User consent required for Drive access")
