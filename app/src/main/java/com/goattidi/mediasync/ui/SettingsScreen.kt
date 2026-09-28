package com.goattidi.mediasync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.goattidi.mediasync.data.db.SyncStatus
import com.goattidi.mediasync.data.repo.DriveLayout

private fun layoutLabel(layout: DriveLayout): String = when (layout) {
    DriveLayout.FLAT -> "All in one folder"
    DriveLayout.BY_MONTH -> "By upload month (e.g. 2026-07)"
    DriveLayout.MIRROR_LOCAL -> "By source folder (Camera, WhatsApp…)"
}

@Composable
fun SettingsContent(state: MainViewModel.UiState, viewModel: MainViewModel) {
    var folderName by remember(state.folderName) { mutableStateOf(state.folderName) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Google Drive account", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (state.driveConnected) {
                    null -> "Checking…"
                    true -> "✓ Connected"
                    false -> "Not connected — uploads wait until you connect"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.driveConnected) {
                    true -> statusColor(SyncStatus.SYNCED)
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            )
            if (state.driveConnected == false) {
                TextButton(onClick = { viewModel.connectDrive() }) { Text("Connect Google Drive") }
            }
        }

        HorizontalDivider()

        Text("Google Drive destination", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = folderName,
            onValueChange = { folderName = it },
            label = { Text("Folder name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = { viewModel.saveFolderName(folderName) },
            enabled = folderName.isNotBlank() && folderName.trim() != state.folderName
        ) { Text("Save folder name") }

        HorizontalDivider()

        Text("Layout inside the folder", style = MaterialTheme.typography.titleMedium)
        DriveLayout.entries.forEach { layout ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setLayout(layout) }
            ) {
                RadioButton(selected = state.layout == layout, onClick = { viewModel.setLayout(layout) })
                Text(layoutLabel(layout))
            }
        }

        HorizontalDivider()

        Text("Upload conditions", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only")
                Text(
                    "Upload only on unmetered networks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.wifiOnly, onCheckedChange = { viewModel.setWifiOnly(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Charging only")
                Text(
                    "Wait until the phone is charging",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.chargingOnly, onCheckedChange = { viewModel.setChargingOnly(it) })
        }

        HorizontalDivider()

        Text("Duplicate detection", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.dedupFolder.isBlank()) "No Drive folder selected"
            else "Also checking: ${state.dedupFolder}",
            style = MaterialTheme.typography.bodyMedium
        )
        Row {
            TextButton(onClick = { viewModel.openFolderPicker() }) { Text("Choose Drive folder…") }
            if (state.dedupFolder.isNotBlank()) {
                TextButton(onClick = { viewModel.clearDedupFolder() }) { Text("Clear") }
            }
        }
        TextButton(onClick = { viewModel.relinkFromDrive() }) { Text("Rebuild duplicate index") }
        Text(
            "Rebuild scans this app's own uploads (any folder) plus the folder tree above — " +
                "including files uploaded by other tools — and indexes their checksums. " +
                "Anything whose exact bytes are already on Drive is marked synced instead of " +
                "re-uploaded. The app reads only the tree you name here, nothing else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text(
            "Changes apply to future uploads only — files already on Drive stay where they are. " +
                "Moving or renaming files on your phone never re-uploads them: identical content " +
                "is recognized by checksum and re-linked automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    state.folderPicker?.let { picker -> FolderPickerDialog(picker, viewModel) }
}

@Composable
private fun FolderPickerDialog(picker: MainViewModel.FolderPicker, viewModel: MainViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.pickerDismiss() },
        title = {
            Text(
                picker.breadcrumb.joinToString(" / ") { it.second },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            if (picker.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    if (picker.breadcrumb.size > 1) {
                        item {
                            Text(
                                "⬆  ..",
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { viewModel.pickerUp() }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    if (picker.folders.isEmpty()) {
                        item {
                            Text(
                                "No subfolders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    items(picker.folders, key = { it.id }) { folder ->
                        Text(
                            "📁  ${folder.name ?: "(unnamed)"}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { viewModel.pickerEnter(folder) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.pickerSelect() }, enabled = !picker.loading) {
                Text("Select this folder")
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.pickerDismiss() }) { Text("Cancel") }
        }
    )
}
