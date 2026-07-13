package com.goattidi.mediasync.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
private fun statusColor(status: SyncStatus): Color = when (status) {
    SyncStatus.NOT_UPLOADED -> Color(0xFF9E9E9E)          // grey
    SyncStatus.QUEUED -> Color(0xFF2196F3)                // blue (static while waiting)
    SyncStatus.UPLOADING -> Color(0xFF2196F3)             // blue (breathing, see MediaTile)
    SyncStatus.SYNCED -> Color(0xFF4CAF50)                // green
    SyncStatus.MODIFIED_SINCE_UPLOAD -> Color(0xFFFFC107) // amber: needs re-upload
    SyncStatus.ORPHANED -> Color(0xFFFFC107)              // amber: gone from Drive
    SyncStatus.FAILED -> Color(0xFFF44336)                // red
}

@Composable
fun GalleryContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
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
                Text("No media found — pull the Scan action or check permissions")
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
                    onRetry = { viewModel.retryFailed(record.mediaStoreId) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    record: SyncRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelect: () -> Unit,
    onRetry: () -> Unit
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
                if (selected) Modifier.border(4.dp, MaterialTheme.colorScheme.primary)
                else Modifier.border(2.dp, statusBorder)
            )
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        record.status == SyncStatus.FAILED -> onRetry()
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
            text = statusBadge(record.status) +
                if (record.status == SyncStatus.FAILED) " retry" else "",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        if (selected) {
            Text(
                "✓",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}
