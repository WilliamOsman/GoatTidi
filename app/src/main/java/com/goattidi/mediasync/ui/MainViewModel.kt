package com.goattidi.mediasync.ui

import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.drive.DriveAuthConsentRequired
import com.goattidi.mediasync.data.drive.DriveAuthProvider
import com.goattidi.mediasync.data.drive.DriveClient
import com.goattidi.mediasync.data.drive.DriveException
import com.goattidi.mediasync.data.drive.DriveFile
import com.goattidi.mediasync.data.repo.DriveLayout
import com.goattidi.mediasync.data.repo.SyncSettings
import com.goattidi.mediasync.data.repo.SyncStateRepository
import com.goattidi.mediasync.sync.ReclaimEngine
import com.goattidi.mediasync.sync.SyncScheduler
import com.goattidi.mediasync.sync.VerifyEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

enum class Screen { GALLERY, RECLAIM, SETTINGS, QUEUE }

enum class Filter(val label: String) {
    ALL("All"),
    IMAGES("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    UNSYNCED("Unsynced"),
    SYNCED("Synced")
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: SyncStateRepository,
    private val scheduler: SyncScheduler,
    private val verifyEngine: VerifyEngine,
    private val reclaimEngine: ReclaimEngine,
    private val authProvider: DriveAuthProvider,
    private val settings: SyncSettings,
    private val driveClient: DriveClient
) : ViewModel() {

    /** One level of the Drive folder browser. Breadcrumb starts at ("root", "My Drive"). */
    data class FolderPicker(
        val breadcrumb: List<Pair<String, String>>,
        val folders: List<DriveFile> = emptyList(),
        val loading: Boolean = true
    )

    data class UiState(
        val records: List<SyncRecord> = emptyList(),
        val totalCount: Int = 0,
        val syncedCount: Int = 0,
        /** Files the user has sent for upload: everything that has left NOT_UPLOADED. */
        val uploadSelectedCount: Int = 0,
        val screen: Screen = Screen.GALLERY,
        val filter: Filter = Filter.ALL,
        val selected: Set<Long> = emptySet(),
        val busy: Boolean = false,
        val message: String? = null,
        val consentIntent: PendingIntent? = null,
        val reclaimCandidates: List<SyncRecord> = emptyList(),
        val folderName: String = SyncSettings.DEFAULT_FOLDER_NAME,
        val layout: DriveLayout = DriveLayout.FLAT,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false,
        val dedupFolder: String = "",
        val folderPicker: FolderPicker? = null,
        /** Active work: UPLOADING first, then QUEUED, then FAILED. */
        val queue: List<SyncRecord> = emptyList()
    ) {
        val selectionMode: Boolean get() = selected.isNotEmpty()
    }

    private val ui = MutableStateFlow(UiState())

    private data class Prefs(
        val folderName: String,
        val layout: DriveLayout,
        val wifiOnly: Boolean,
        val chargingOnly: Boolean,
        val dedupFolder: String
    )

    private val prefs = combine(
        settings.folderName, settings.layout, settings.wifiOnly, settings.chargingOnly, settings.dedupFolder
    ) { name, layout, wifi, charging, dedup -> Prefs(name, layout, wifi, charging, dedup) }

    val state: StateFlow<UiState> = combine(repository.observeAll(), prefs, ui) { records, p, s ->
        val visible = records
            .filter { matches(it, s.filter) }
            .sortedByDescending { it.dateTaken }
        s.copy(
            records = visible,
            totalCount = records.size,
            syncedCount = records.count { it.status == SyncStatus.SYNCED },
            uploadSelectedCount = records.count { it.status != SyncStatus.NOT_UPLOADED },
            folderName = p.folderName,
            layout = p.layout,
            wifiOnly = p.wifiOnly,
            chargingOnly = p.chargingOnly,
            dedupFolder = p.dedupFolder,
            queue = records
                .filter {
                    it.status == SyncStatus.UPLOADING || it.status == SyncStatus.QUEUED ||
                        it.status == SyncStatus.FAILED
                }
                .sortedBy {
                    when (it.status) {
                        SyncStatus.UPLOADING -> 0
                        SyncStatus.QUEUED -> 1
                        else -> 2
                    }
                }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Uris the UI should route through MediaStore.createDeleteRequest. */
    private val _deleteRequests = MutableSharedFlow<List<Uri>>()
    val deleteRequests: SharedFlow<List<Uri>> = _deleteRequests

    private fun matches(record: SyncRecord, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.IMAGES -> record.mediaType == MediaType.IMAGE
        Filter.VIDEOS -> record.mediaType == MediaType.VIDEO
        Filter.AUDIO -> record.mediaType == MediaType.AUDIO
        Filter.UNSYNCED -> record.status != SyncStatus.SYNCED
        Filter.SYNCED -> record.status == SyncStatus.SYNCED
    }

    fun refresh() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            runCatching { repository.scanAndReconcile() }
                .onFailure { e -> ui.update { it.copy(message = "Scan failed: ${e.message}") } }
            ui.update { it.copy(busy = false) }
        }
    }

    fun setFilter(filter: Filter) = ui.update { it.copy(filter = filter) }

    fun toggleSelect(id: Long) = ui.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAllUnsynced() {
        val ids = state.value.records
            .filter { it.status != SyncStatus.SYNCED && it.status != SyncStatus.UPLOADING }
            .map { it.mediaStoreId }
        ui.update { it.copy(selected = it.selected + ids) }
    }

    fun clearSelection() = ui.update { it.copy(selected = emptySet()) }

    fun syncSelected() {
        viewModelScope.launch {
            val ids = ui.value.selected.toList()
            if (ids.isEmpty()) return@launch
            val queued = repository.enqueue(ids)
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
            ui.update { it.copy(selected = emptySet(), message = "$queued file(s) queued for upload") }
        }
    }

    fun retryFailed(id: Long) {
        viewModelScope.launch {
            repository.enqueue(listOf(id))
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
        }
    }

    fun verify() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            val text = try {
                val s = verifyEngine.verifyAll()
                "Verify: ${s.confirmedSynced} confirmed, ${s.orphaned} orphaned, " +
                    "${s.modified} modified, ${s.stillUnverified} unverified of ${s.checked}"
            } catch (e: Exception) {
                driveErrorMessage(e)
            }
            ui.update { it.copy(busy = false, message = text) }
        }
    }

    // ---- Drive folder picker ----

    fun openFolderPicker() = loadPickerLevel(listOf("root" to "My Drive"))

    fun pickerEnter(folder: DriveFile) {
        val current = ui.value.folderPicker ?: return
        loadPickerLevel(current.breadcrumb + (folder.id to (folder.name ?: "(unnamed)")))
    }

    fun pickerUp() {
        val current = ui.value.folderPicker ?: return
        if (current.breadcrumb.size > 1) loadPickerLevel(current.breadcrumb.dropLast(1))
    }

    fun pickerDismiss() = ui.update { it.copy(folderPicker = null) }

    fun pickerSelect() {
        val picker = ui.value.folderPicker ?: return
        val (id, _) = picker.breadcrumb.last()
        val path = picker.breadcrumb.drop(1).joinToString("/") { it.second }.ifEmpty { "My Drive" }
        viewModelScope.launch {
            settings.setDedupFolder(path, id)
            ui.update {
                it.copy(
                    folderPicker = null,
                    message = "Duplicate-check folder: $path — run \"Rebuild duplicate index\" to scan it"
                )
            }
        }
    }

    fun clearDedupFolder() {
        viewModelScope.launch {
            settings.setDedupFolder("", "")
            ui.update { it.copy(message = "Duplicate-check folder cleared") }
        }
    }

    private fun loadPickerLevel(breadcrumb: List<Pair<String, String>>) {
        viewModelScope.launch {
            ui.update { it.copy(folderPicker = FolderPicker(breadcrumb)) }
            try {
                val folders = driveClient.listFolders(breadcrumb.last().first)
                ui.update { it.copy(folderPicker = FolderPicker(breadcrumb, folders, loading = false)) }
            } catch (e: Exception) {
                ui.update { it.copy(folderPicker = null, message = driveErrorMessage(e)) }
            }
        }
    }

    /**
     * Rebuilds the dedup index: the app's own uploads (any folder) plus, if
     * configured, the user-designated external folder tree (rclone uploads etc.).
     */
    fun relinkFromDrive() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            val text = try {
                val own = verifyEngine.importLedgerFromDrive()
                val externalId = settings.dedupFolderId.first().trim()
                val externalPath = settings.dedupFolder.first().trim()
                val imported = when {
                    externalId.isNotEmpty() -> verifyEngine.importExternalFolderById(externalId)
                    externalPath.isNotEmpty() -> verifyEngine.importExternalFolder(externalPath)
                    else -> 0
                }
                val externalMsg = when {
                    externalPath.isEmpty() -> ""
                    imported == null -> " · folder \"$externalPath\" is gone from Drive — re-select it"
                    else -> " · indexed $imported file(s) under \"$externalPath\""
                }
                "Re-linked $own app upload(s)$externalMsg — duplicates of these won't re-upload"
            } catch (e: Exception) {
                driveErrorMessage(e)
            }
            ui.update { it.copy(busy = false, message = text) }
        }
    }

    fun connectDrive() {
        viewModelScope.launch {
            val text = try {
                authProvider.accessToken()
                "Google Drive connected"
            } catch (e: Exception) {
                driveErrorMessage(e)
            }
            ui.update { it.copy(message = text) }
        }
    }

    fun onConsentResult(granted: Boolean) {
        ui.update { it.copy(consentIntent = null) }
        if (granted) connectDrive()
    }

    fun openReclaim() {
        viewModelScope.launch {
            ui.update { it.copy(screen = Screen.RECLAIM, reclaimCandidates = reclaimEngine.candidates()) }
        }
    }

    fun openGallery() = ui.update { it.copy(screen = Screen.GALLERY) }

    fun openSettings() = ui.update { it.copy(screen = Screen.SETTINGS) }

    fun openQueue() = ui.update { it.copy(screen = Screen.QUEUE) }

    fun cancelQueued(id: Long) {
        viewModelScope.launch { repository.cancelQueued(listOf(id)) }
    }

    fun saveFolderName(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().ifEmpty { SyncSettings.DEFAULT_FOLDER_NAME }
            settings.setFolderName(trimmed)
            ui.update { it.copy(message = "Future uploads go to \"$trimmed\"") }
        }
    }

    fun setLayout(layout: DriveLayout) {
        viewModelScope.launch { settings.setLayout(layout) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setWifiOnly(value)
            rescheduleIfQueueActive()
        }
    }

    fun setChargingOnly(value: Boolean) {
        viewModelScope.launch {
            settings.setChargingOnly(value)
            rescheduleIfQueueActive()
        }
    }

    /** Constraint changes must re-enqueue pending work or the old constraints keep applying. */
    private suspend fun rescheduleIfQueueActive() {
        if (repository.uploadBatch().isNotEmpty()) {
            scheduler.scheduleNow(settings.wifiOnly.first(), settings.chargingOnly.first())
        }
    }

    init {
        // Revive any leftover queue on app open. Background starts can be denied
        // (OEM battery managers) and WorkManager backoff can stretch to hours;
        // opening the app is clear intent to sync, and a foreground (re)schedule
        // resets the backoff and is always allowed to start the upload service.
        viewModelScope.launch { rescheduleIfQueueActive() }
    }

    fun requestDelete(id: Long) {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            when (val gate = try { reclaimEngine.confirmSafeToDelete(id) } catch (e: Exception) {
                ReclaimEngine.Gate.Blocked(driveErrorMessage(e))
            }) {
                is ReclaimEngine.Gate.Safe -> {
                    repository.getById(id)?.let { _deleteRequests.emit(listOf(Uri.parse(it.localUri))) }
                }
                is ReclaimEngine.Gate.Blocked ->
                    ui.update { it.copy(message = "Not deleted: ${gate.reason}") }
            }
            ui.update { it.copy(busy = false) }
        }
    }

    /**
     * Gates every reclaim candidate (invariant #3 per file), then issues ONE batch
     * delete request — a single system confirmation dialog instead of one per file.
     */
    fun requestDeleteAll() {
        viewModelScope.launch {
            ui.update { it.copy(busy = true) }
            // Bounded parallelism: ~5 concurrent checks saturates OkHttp's per-host
            // limit without inviting Drive rate limits or thrashing disk hashing
            val gate = Semaphore(5)
            val results = coroutineScope {
                reclaimEngine.candidates().map { candidate ->
                    async {
                        gate.withPermit {
                            candidate to try {
                                reclaimEngine.confirmSafeToDelete(candidate.mediaStoreId)
                            } catch (e: Exception) {
                                ReclaimEngine.Gate.Blocked(driveErrorMessage(e))
                            }
                        }
                    }
                }.awaitAll()
            }
            val safeUris = results
                .filter { (_, g) -> g is ReclaimEngine.Gate.Safe }
                .map { (candidate, _) -> Uri.parse(candidate.localUri) }
            val blocked = results.size - safeUris.size
            if (safeUris.isEmpty()) {
                ui.update {
                    it.copy(
                        busy = false,
                        message = if (blocked > 0) "Nothing deleted — $blocked file(s) failed the safety re-check"
                        else "Nothing to delete"
                    )
                }
            } else {
                if (blocked > 0) {
                    ui.update { it.copy(message = "$blocked file(s) skipped by the safety re-check") }
                }
                _deleteRequests.emit(safeUris)
                ui.update { it.copy(busy = false) }
            }
        }
    }

    fun onDeleteCompleted() {
        viewModelScope.launch {
            runCatching { repository.scanAndReconcile() }
            ui.update { it.copy(reclaimCandidates = reclaimEngine.candidates(), message = "Space reclaimed") }
        }
    }

    fun clearMessage() = ui.update { it.copy(message = null) }

    private fun driveErrorMessage(e: Throwable): String = when (e) {
        is DriveAuthConsentRequired -> {
            ui.update { it.copy(consentIntent = e.pendingIntent) }
            "Google Drive needs your authorization…"
        }
        is DriveException.ServiceDisabled ->
            "Drive API is not enabled for this app's Google Cloud project (see setup docs)"
        is DriveException.StorageQuotaExceeded -> "Google Drive storage is full"
        is DriveException -> "Google Drive error: ${e.message}"
        else -> "Error: ${e.message}"
    }
}
