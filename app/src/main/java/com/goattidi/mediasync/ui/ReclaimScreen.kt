package com.goattidi.mediasync.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.goattidi.mediasync.data.db.SyncRecord
import com.goattidi.mediasync.data.db.SyncStatus
import java.util.Locale

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.US, "%d KB", bytes / 1_000)
}

private val DriveColumnWidth = 84.dp
private val DeleteColumnWidth = 104.dp

/** Drive state from the record itself, so the column can't claim more than the DB knows. */
@Composable
private fun DriveStatusCell(record: SyncRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        if (record.status == SyncStatus.SYNCED) {
            Text(
                "${statusBadge(record.status)} Synced",
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(record.status)
            )
            record.uploadedAt?.let {
                Text(
                    DateUtils.formatDateTime(
                        context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "${statusBadge(record.status)} Not synced",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ReclaimContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Every delete re-checks that the file's current bytes are still on Drive " +
                "before anything is removed.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
        if (state.reclaimCandidates.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No verified-synced files to reclaim")
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${state.reclaimCandidates.size} files · " +
                    formatSize(state.reclaimCandidates.sumOf { it.sizeBytes }),
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { viewModel.requestDeleteAll() }, enabled = !state.busy) {
                Text("Delete all (one confirmation)")
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val header = MaterialTheme.typography.labelMedium
            val headerColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text("File", style = header, color = headerColor, modifier = Modifier.weight(1f))
            Text("On Drive", style = header, color = headerColor, modifier = Modifier.width(DriveColumnWidth))
            Spacer(Modifier.width(DeleteColumnWidth))
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.reclaimCandidates, key = { it.mediaStoreId }) { record ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            record.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatSize(record.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DriveStatusCell(record, Modifier.width(DriveColumnWidth))
                    TextButton(
                        onClick = { viewModel.requestDelete(record.mediaStoreId) },
                        modifier = Modifier.width(DeleteColumnWidth)
                    ) {
                        Text("Delete local")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
