package com.goattidi.mediasync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { viewModel.relinkFromDrive() }) { Text("Re-link uploads from Drive") }
        Text(
            "Scans every file this app has uploaded to Drive — in any folder — and " +
                "rebuilds the duplicate-detection index from their checksums. Use after " +
                "reinstalling the app or if uploads seem to be repeating.",
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
}
