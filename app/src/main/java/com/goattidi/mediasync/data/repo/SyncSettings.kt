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

@Singleton
class SyncSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyWifiOnly = booleanPreferencesKey("wifi_only")
    private val keyChargingOnly = booleanPreferencesKey("charging_only")
    private val keyFolderName = stringPreferencesKey("drive_folder_name")
    private val keyFolderId = stringPreferencesKey("drive_folder_id")

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[keyWifiOnly] ?: true }
    val chargingOnly: Flow<Boolean> = context.dataStore.data.map { it[keyChargingOnly] ?: false }
    val folderName: Flow<String> = context.dataStore.data.map { it[keyFolderName] ?: DEFAULT_FOLDER_NAME }

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
