package com.goattidi.mediasync.data.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production token source backed by Play Services AuthorizationClient (the
 * GoogleSignIn SDK is deprecated). Requests drive.file scope only.
 *
 * When Google needs interactive consent, throws [DriveAuthConsentRequired]
 * carrying the PendingIntent the UI must launch.
 */
@Singleton
class GoogleDriveAuthProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DriveAuthProvider {

    override suspend fun accessToken(forceRefresh: Boolean): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE), Scope(DRIVE_READONLY_SCOPE)))
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
