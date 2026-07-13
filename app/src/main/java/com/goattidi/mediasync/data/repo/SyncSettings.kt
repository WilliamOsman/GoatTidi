package com.goattidi.mediasync.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** How uploads are organized inside the destination folder (§4.4). */
enum class DriveLayout {
    /** Everything directly in the destination folder. */
    FLAT,

    /** One subfolder per upload month, e.g. "2026-07". */
    BY_MONTH,

    /** One subfolder per local source folder, e.g. "Camera", "WhatsApp Video". */
    MIRROR_LOCAL
}

@Singleton
class SyncSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyChargingOnly = booleanPreferencesKey("charging_only")
    private val keyFolderName = stringPreferencesKey("drive_folder_name")
    private val keyFolderId = stringPreferencesKey("drive_folder_id")
    private val keyLayout = stringPreferencesKey("drive_layout")

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[keyWifiOnly] ?: true }
    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[keyChargingOnly] ?: false }
    val folderName: Flow<String> = context.dataStore.data.map { it[keyFolderName] ?: DEFAULT_FOLDER_NAME }
    val layout: Flow<DriveLayout> = context.dataStore.data.map { prefs ->
        prefs[keyLayout]?.let { runCatching { DriveLayout.valueOf(it) }.getOrNull() } ?: DriveLayout.FLAT
    }

    suspend fun setLayout(value: DriveLayout) {
        context.dataStore.edit { it[keyLayout] = value.name }
    }

    private val keyDedupFolder = stringPreferencesKey("dedup_folder_path")

    /** Drive folder tree (slash-separated path, e.g. "Media/Videos") scanned for duplicates. Empty = off. */
    val dedupFolder: Flow<String> = context.dataStore.data.map { it[keyDedupFolder] ?: "" }

    suspend fun setDedupFolder(path: String) {
        context.dataStore.edit { it[keyDedupFolder] = path.trim() }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[keyWifiOnly] = value }
    }

    suspend fun setChargingOnly(value: Boolean) {
        context.dataStore.edit { it[keyChargingOnly] = value }
    }

    /** Changing the destination folder invalidates the cached folder id. */
    suspend fun setFolderName(value: String) {
        context.dataStore.edit {
            it[keyFolderName] = value
            it.remove(keyFolderId)
        }
    }

    suspend fun cachedFolderId(): String? = context.dataStore.data.first()[keyFolderId]

    suspend fun setCachedFolderId(id: String) {
        context.dataStore.edit { it[keyFolderId] = id }
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "Phone Media"
    }
}
