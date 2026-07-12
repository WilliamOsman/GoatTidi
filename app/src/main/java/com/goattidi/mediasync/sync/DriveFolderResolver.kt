package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.repo.SyncSettings
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the Drive folder id uploads should land in (null = Drive root). */
fun interface DriveFolderResolver {
    suspend fun resolveFolderId(): String?
}

/**
 * Finds-or-creates the user's destination folder (default "Phone Media") and caches
 * its id. This app owns its root folder — it is deliberately separate from folders
 * other tools manage (§4.4).
 */
@Singleton
class SettingsDriveFolderResolver @Inject constructor(
    private val settings: SyncSettings,
    private val client: DriveClient
) : DriveFolderResolver {

    override suspend fun resolveFolderId(): String? {
        settings.cachedFolderId()?.let { return it }
        val id = client.ensureFolder(settings.folderName.first())
        settings.setCachedFolderId(id)
        return id
    }
}
