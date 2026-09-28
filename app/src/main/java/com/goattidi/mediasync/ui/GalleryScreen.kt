package com.goattidi.mediasync.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.goattidi.mediasync.data.db.MediaType
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus

/** Status badges per spec §4.2. */
fun statusBadge(status: SyncStatus): String = when (status) {
    SyncStatus.NOT_UPLOADED -> "○"
    SyncStatus.QUEUED -> "⏳"
    SyncStatus.UPLOADING -> "↑"
    SyncStatus.SYNCED -> "✓"
    SyncStatus.MODIFIED_SINCE_UPLOAD -> "⚠"
    SyncStatus.FAILED -> "✗"
    SyncStatus.ORPHANED -> "👻"
}

/** Border color per status: grey unsynced, blue syncing, green synced, amber/red trouble. */
fun statusColor(status: SyncStatus): Color = when (status) {
    SyncStatus.NOT_UPLOADED -> Color(0xFF9E9E9E)          // grey
    SyncStatus.QUEUED -> Color(0xFF2196F3)                // blue (static while waiting)
    SyncStatus.UPLOADING -> Color(0xFF2196F3)             // blue (breathing, see MediaTile)
    SyncStatus.SYNCED -> Color(0xFF4CAF50)                // green
    SyncStatus.MODIFIED_SINCE_UPLOAD -> Color(0xFFFFC107) // amber: needs re-upload
    SyncStatus.ORPHANED -> Color(0xFFFFC107)              // amber: gone from Drive
    SyncStatus.FAILED -> Color(0xFFF44336)                // red
}

/** "1:23" / "1:02:45" like the system gallery. */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun GalleryContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    val context = LocalContext.current

    fun openInViewer(record: SyncRecord) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(record.localUri), record.mimeType.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    var failureDetail by remember { mutableStateOf<SyncRecord?>(null) }
    failureDetail?.let { record ->
        FailureDialog(
            record = record,
            onRetry = {
                viewModel.retryFailed(record.mediaStoreId)
                failureDetail = null
            },
            onOpen = {
                openInViewer(record)
                failureDetail = null
            },
            onDismiss = { failureDetail = null }
        )
    }

    Column(Modifier.fillMaxSize()) {
        val active = state.queue.filter {
            it.status == SyncStatus.UPLOADING || it.status == SyncStatus.QUEUED
        }
        if (active.isNotEmpty()) {
            val uploading = active.filter { it.status == SyncStatus.UPLOADING }
            val rate = uploading.sumOf { it.uploadRateBps }
            Text(
                "↑ ${uploading.size} uploading · ${active.size - uploading.size} waiting" +
                    (if (rate > 0) " · ${formatRate(rate)}" else "") + "  —  tap for details",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { viewModel.openQueue() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Filter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label) }
                )
            }
            TextButton(onClick = { viewModel.selectAllUnsynced() }) { Text("Select unsynced") }
        }

        if (state.records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.totalCount > 0) {
                    // Media exists; the active filter just has no matches
                    Text("No ${state.filter.label.lowercase()} files")
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No photos, videos, or audio found on this device")
                        TextButton(onClick = { viewModel.refresh() }) { Text("Scan again") }
                    }
                }
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.records, key = { it.mediaStoreId }) { record ->
                MediaTile(
                    record = record,
                    selected = record.mediaStoreId in state.selected,
                    selectionMode = state.selectionMode,
                    onToggleSelect = { viewModel.toggleSelect(record.mediaStoreId) },
                    onShowFailure = { failureDetail = record },
                    onOpen = { openInViewer(record) }
                )
            }
        }
    }
}

/** Spec §4.2: tapping a failed file shows the reason and offers a retry. */
@Composable
private fun FailureDialog(
    record: SyncRecord,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload failed", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.fileName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    record.failureReason ?: "No reason was recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(onClick = onRetry) { Text("Retry") } },
        dismissButton = { TextButton(onClick = onOpen) { Text("Open") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    record: SyncRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onShowFailure: () -> Unit,
    onOpen: () -> Unit
) {
    // "Breathing" border while actively uploading
    val statusBorder = if (record.status == SyncStatus.UPLOADING) {
        val breath = rememberInfiniteTransition(label = "uploading-breath")
        val alpha by breath.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 850), RepeatMode.Reverse),
            label = "uploading-alpha"
        )
        statusColor(record.status).copy(alpha = alpha)
    } else {
        statusColor(record.status)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .then(
                if (selected) Modifier.border(6.dp, MaterialTheme.colorScheme.primary)
                else Modifier.border(4.dp, statusBorder)
            )
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        record.status == SyncStatus.FAILED -> onShowFailure()
                        else -> onOpen()
                    }
                },
                onLongClick = onToggleSelect
            )
    ) {
        if (record.mediaType == MediaType.AUDIO) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎵", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        record.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        } else {
            AsyncImage(
                model = record.localUri,
                contentDescription = record.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(if (selected) 0.6f else 1f)
            )
        }
        Text(
            text = statusBadge(record.status),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        if (record.mediaType == MediaType.VIDEO) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("▶", color = Color.White, fontSize = 8.sp)
                if (record.durationMs > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        formatDuration(record.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (selected) {
            Text(
                "✓",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}
