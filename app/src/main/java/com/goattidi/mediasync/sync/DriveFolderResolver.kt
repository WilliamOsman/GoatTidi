package com.goattidi.mediasync.sync

import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the Drive folder id a given upload should land in (null = Drive root). */
fun interface DriveFolderResolver {
    suspend fun resolveFolderId(record: SyncRecord): String?
}

/**
 * Resolves the destination per the user's settings: the root folder (default
 * "Phone Media", find-or-create, id cached) plus an optional subfolder per the
 * chosen layout — upload month ("2026-07") or the file's local source folder
 * ("Camera", "WhatsApp Video"). This app owns its root folder — deliberately
 * separate from folders other tools manage (§4.4).
 */
@Singleton
class SettingsDriveFolderResolver @Inject constructor(
    private val settings: SyncSettings,
    private val client: DriveClient
) : DriveFolderResolver {

    private val childCache = ConcurrentHashMap<String, String>()

    /** Injectable for tests; wall clock in production. */
    internal var clock: () -> Long = System::currentTimeMillis

    override suspend fun resolveFolderId(record: SyncRecord): String? {
        val rootId = settings.cachedFolderId()
            ?: client.ensureFolder(settings.folderName.first()).also { settings.setCachedFolderId(it) }
        val childName = when (settings.layout.first()) {
            DriveLayout.FLAT -> return rootId
            DriveLayout.BY_MONTH -> monthFolder(clock())
            DriveLayout.MIRROR_LOCAL -> sourceFolder(record)
        }
        val cacheKey = "$rootId/$childName"
        childCache[cacheKey]?.let { return it }
        val childId = client.ensureFolder(childName, parentId = rootId)
        childCache[cacheKey] = childId
        return childId
    }

    private fun monthFolder(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(Date(timestamp))

    private fun sourceFolder(record: SyncRecord): String =
        File(record.filePath).parentFile?.name?.takeIf { it.isNotBlank() } ?: "Other"
}
