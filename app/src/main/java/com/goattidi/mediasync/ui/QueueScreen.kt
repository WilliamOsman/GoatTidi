package com.goattidi.mediasync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import java.util.Locale

fun formatRate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bps / 1_000_000.0)
    bps >= 1_000 -> String.format(Locale.US, "%.0f KB/s", bps / 1_000.0)
    else -> "$bps B/s"
}

private fun formatEta(remainingBytes: Long, bps: Long): String {
    if (bps <= 0) return ""
    val sec = remainingBytes / bps
    return when {
        sec >= 3600 -> String.format(Locale.US, " · ~%dh %dm left", sec / 3600, (sec % 3600) / 60)
        sec >= 60 -> String.format(Locale.US, " · ~%dm %ds left", sec / 60, sec % 60)
        else -> " · ~${sec}s left"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%d KB", bytes / 1_000)
}

@Composable
fun QueueContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    if (state.queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing uploading, queued, or failed")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.queue, key = { it.mediaStoreId }) { record ->
            QueueRow(record, viewModel)
            HorizontalDivider()
        }
    }
}

@Composable
private fun QueueRow(record: SyncRecord, viewModel: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "${statusBadge(record.status)}  ${record.fileName}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            when (record.status) {
                SyncStatus.QUEUED -> TextButton(
                    onClick = { viewModel.cancelQueued(record.mediaStoreId) }
                ) { Text("Cancel") }
                SyncStatus.FAILED -> TextButton(
                    onClick = { viewModel.retryFailed(record.mediaStoreId) }
                ) { Text("Retry") }
                else -> {}
            }
        }
        when (record.status) {
            SyncStatus.UPLOADING -> {
                val hasProgress = record.sizeBytes > 0 && record.bytesUploaded > 0
                if (hasProgress) {
                    LinearProgressIndicator(
                        progress = {
                            (record.bytesUploaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                val pct = if (record.sizeBytes > 0) record.bytesUploaded * 100 / record.sizeBytes else 0
                Text(
                    buildString {
                        append("$pct% · ${formatBytes(record.bytesUploaded)} of ${formatBytes(record.sizeBytes)}")
                        if (record.uploadRateBps > 0) {
                            append(" · ${formatRate(record.uploadRateBps)}")
                            append(formatEta(record.sizeBytes - record.bytesUploaded, record.uploadRateBps))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SyncStatus.QUEUED -> Text(
                "Waiting · ${formatBytes(record.sizeBytes)}" +
                    (record.failureReason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SyncStatus.FAILED -> Text(
                record.failureReason ?: "Failed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            else -> {}
        }
    }
}
