package com.goattidi.mediasync

import com.goattidi.mediasync.data.drive.DriveAuthProvider

class FakeDriveAuth : DriveAuthProvider {
    var refreshes = 0
        private set

    override suspend fun accessToken(forceRefresh: Boolean): String {
        if (forceRefresh) refreshes++
        return if (refreshes == 0) "token-initial" else "token-refreshed-$refreshes"
    }
}
