package com.goattidi.mediasync.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    LaunchedEffect(Unit) {
        viewModel.deleteRequests.collect { uris ->
            if (Build.VERSION.SDK_INT >= 30) {
                // The system consent dialog — the only way to delete media we didn't create
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } else {
                uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                viewModel.onDeleteCompleted()
            }
        }
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
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.screen == Screen.RECLAIM -> "Free up space"
                            state.screen == Screen.SETTINGS -> "Settings"
                            state.screen == Screen.QUEUE -> "Upload queue"
                            state.selectionMode -> "${state.selected.size} selected"
                            else -> "GoatTidi · ${state.syncedCount}/${state.totalCount} synced"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    when {
                        state.screen != Screen.GALLERY ->
                            TextButton(onClick = { viewModel.openGallery() }) { Text("Gallery") }
                        state.selectionMode -> {
                            TextButton(onClick = { viewModel.syncSelected() }) { Text("Upload") }
                            TextButton(onClick = { viewModel.clearSelection() }) { Text("Clear") }
                        }
                        else -> {
                            TextButton(onClick = { viewModel.verify() }) { Text("Verify") }
                            TextButton(onClick = { viewModel.openReclaim() }) { Text("Free space") }
                            TextButton(onClick = { viewModel.connectDrive() }) { Text("Drive") }
                            TextButton(onClick = { viewModel.openSettings() }) { Text("⚙") }
                        }
                    }
                }
            )
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
private fun PermissionExplainer(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GoatTidi needs access to your photos, videos, and audio recordings to show what's backed up to Google Drive.")
        Box { TextButton(onClick = onRequest) { Text("Grant access") } }
    }
}
