package com.goattidi.mediasync.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

private fun hasAnyMediaPermission(context: Context): Boolean =
    requiredPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: MainViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var permissionGranted by remember { mutableStateOf(hasAnyMediaPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Partial grant (Android 14 "selected photos") still lets us scan the subset
        permissionGranted = results.values.any { it }
        if (permissionGranted) viewModel.refresh()
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeleteCompleted()
    }

    LaunchedEffect(Unit) {
        if (permissionGranted) viewModel.refresh()
        else permissionLauncher.launch(requiredPermissions())
    }

    LaunchedEffect(state.consentIntent) {
        state.consentIntent?.let { pi ->
            consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    var pendingLegacyDelete by remember { mutableStateOf<List<Uri>>(emptyList()) }

    LaunchedEffect(Unit) {
        viewModel.deleteRequests.collect { uris ->
            if (Build.VERSION.SDK_INT >= 30) {
                // The system consent dialog — the only way to delete media we didn't create
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                pendingLegacyDelete = uris
            }
        }
    }

    if (pendingLegacyDelete.isNotEmpty()) {
        LegacyDeleteConfirm(
            count = pendingLegacyDelete.size,
            onConfirm = {
                pendingLegacyDelete.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                pendingLegacyDelete = emptyList()
                viewModel.onDeleteCompleted()
            },
            onDismiss = { pendingLegacyDelete = emptyList() }
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = state.screen != Screen.GALLERY) { viewModel.openGallery() }

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth()) {
                TopAppBar(
                    navigationIcon = {
                        when {
                            state.screen != Screen.GALLERY ->
                                TextButton(onClick = { viewModel.openGallery() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Gallery")
                                }
                            state.selectionMode ->
                                IconButton(onClick = { viewModel.clearSelection() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                                }
                        }
                    },
                    title = {
                        when {
                            state.screen == Screen.RECLAIM -> Text("Free up space")
                            state.screen == Screen.SETTINGS -> Text("Settings")
                            state.screen == Screen.QUEUE -> Text("Upload queue")
                            state.selectionMode -> Text("${state.selected.size} selected")
                            else -> Column {
                                Text("GoatTidi", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (state.uploadSelectedCount == 0) "None selected"
                                    else "${state.syncedCount}/${state.uploadSelectedCount} synced",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        when {
                            state.screen == Screen.RECLAIM ->
                                OverflowMenu(listOf("Verify with Drive" to viewModel::verify))
                            state.screen != Screen.GALLERY -> {}
                            state.selectionMode ->
                                TextButton(onClick = { viewModel.syncSelected() }) { Text("Upload") }
                            else -> {
                                IconButton(onClick = { viewModel.openSettings() }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                                OverflowMenu(
                                    listOf(
                                        "Upload queue" to viewModel::openQueue,
                                        "Connect Google Drive" to viewModel::connectDrive
                                    )
                                )
                            }
                        }
                    }
                )
                if (state.screen == Screen.GALLERY && !state.selectionMode) {
                    // Overlaid rather than in the title slot so it sits at the true screen center,
                    // independent of the title and action widths
                    TextButton(
                        onClick = { viewModel.openReclaim() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                    ) { Text("Free up space") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !permissionGranted -> PermissionExplainer { permissionLauncher.launch(requiredPermissions()) }
                state.screen == Screen.GALLERY -> GalleryContent(state, viewModel)
                state.screen == Screen.SETTINGS -> SettingsContent(state, viewModel)
                state.screen == Screen.QUEUE -> QueueContent(state, viewModel)
                else -> ReclaimContent(state, viewModel)
            }
        }
    }
}

@Composable
private fun OverflowMenu(items: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        action()
                    }
                )
            }
        }
    }
}

/**
 * Below Android 11 there is no system delete dialog — contentResolver.delete is
 * immediate — so the confirmation §4.5 requires has to come from the app.
 */
@Composable
private fun LegacyDeleteConfirm(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete 1 file from this phone?" else "Delete $count files from this phone?") },
        text = { Text("The local copies are removed. The verified copies on Google Drive are kept.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PermissionExplainer(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GoatTidi needs access to your photos, videos, and audio recordings to show what's backed up to Google Drive.")
        Box { TextButton(onClick = onRequest) { Text("Grant access") } }
    }
}
