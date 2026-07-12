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
import com.goattidi.mediasync.data.drive.DriveException
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class Screen { GALLERY, RECLAIM }

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
    private val settings: SyncSettings
) : ViewModel() {

    data class UiState(
        val records: List<SyncRecord> = emptyList(),
        val totalCount: Int = 0,
        val syncedCount: Int = 0,
        val screen: Screen = Screen.GALLERY,
        val filter: Filter = Filter.ALL,
        val selected: Set<Long> = emptySet(),
        val busy: Boolean = false,
        val message: String? = null,
        val consentIntent: PendingIntent? = null,
        val reclaimCandidates: List<SyncRecord> = emptyList()
    ) {
        val selectionMode: Boolean get() = selected.isNotEmpty()
    }

    private val ui = MutableStateFlow(UiState())

    val state: StateFlow<UiState> = combine(repository.observeAll(), ui) { records, s ->
        val visible = records
            .filter { matches(it, s.filter) }
            .sortedByDescending { it.dateTaken }
        s.copy(
            records = visible,
            totalCount = records.size,
            syncedCount = records.count { it.status == SyncStatus.SYNCED }
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
