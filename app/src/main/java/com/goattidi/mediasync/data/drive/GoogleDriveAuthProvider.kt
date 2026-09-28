package com.goattidi.mediasync.data.drive

import android.content.Context
import com.goattidi.mediasync.data.repo.SyncSettings
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production token source backed by Play Services AuthorizationClient (the
 * GoogleSignIn SDK is deprecated). Requests drive.file scope only, adding
 * drive.readonly just while the user has opted into external duplicate detection.
 *
 * When Google needs interactive consent, throws [DriveAuthConsentRequired]
 * carrying the PendingIntent the UI must launch.
 */
@Singleton
class GoogleDriveAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SyncSettings
) : DriveAuthProvider {

    override suspend fun accessToken(forceRefresh: Boolean): String =
        authorize(includeExternalRead = settings.externalReadEnabled.first())

    override suspend fun authorizeExternalRead() {
        authorize(includeExternalRead = true)
    }

    private suspend fun authorize(includeExternalRead: Boolean): String {
        val scopes = buildList {
            add(Scope(DRIVE_FILE_SCOPE))
            if (includeExternalRead) add(Scope(DRIVE_READONLY_SCOPE))
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build()
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request).await()
        } catch (e: Exception) {
            throw DriveException.AuthFailed("Authorization failed: ${e.message}")
        }
        if (result.hasResolution()) {
            throw DriveAuthConsentRequired(result.pendingIntent)
        }
        return result.accessToken
            ?: throw DriveException.AuthFailed("Authorization returned no access token")
    }
}
