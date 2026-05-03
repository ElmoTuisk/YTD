package com.elmotuisk.ytd.ui.download

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.HistoryItem
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.download.DownloadState
import com.elmotuisk.ytd.ui.theme.YtdError
import java.io.File

@Composable
fun DownloadRoute(
    sharedUrl: String? = null,
    viewModel: DownloadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            viewModel.handleShareIntent(sharedUrl)
        }
    }

    DownloadScreen(
        uiState = uiState,
        outputDir = viewModel.getOutputDirPath(),
        onUrlChanged = viewModel::onUrlChanged,
        onPlatformChanged = viewModel::onPlatformChanged,
        onFormatChanged = viewModel::onFormatChanged,
        onQualityChanged = viewModel::onQualityChanged,
        onPasteClicked = viewModel::onPasteClicked,
        onDownloadClicked = viewModel::onDownloadClicked,
        onCancelClicked = viewModel::onCancelClicked,
        onDownloadCompleted = viewModel::onDownloadCompleted,
        onErrorDismissed = viewModel::onErrorDismissed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    uiState: DownloadScreenState,
    outputDir: String,
    onUrlChanged: (String) -> Unit,
    onPlatformChanged: (Platform) -> Unit,
    onFormatChanged: (DownloadFormat) -> Unit,
    onQualityChanged: (String) -> Unit,
    onPasteClicked: () -> Unit,
    onDownloadClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onDownloadCompleted: () -> Unit,
    onErrorDismissed: () -> Unit,
) {
    val context = LocalContext.current
    var notificationPermissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        // Proceed with download even if denied
        onDownloadClicked()
    }

    val isDownloading = uiState.downloadState is DownloadState.Downloading

    // Handle completion
    LaunchedEffect(uiState.downloadState) {
        if (uiState.downloadState is DownloadState.Completed) {
            onDownloadCompleted()
        }
    }

    // Error dialog
    if (uiState.downloadState is DownloadState.Error) {
        AlertDialog(
            onDismissRequest = onErrorDismissed,
            title = { Text("Download Error") },
            text = { Text((uiState.downloadState as DownloadState.Error).message) },
            confirmButton = {
                TextButton(onClick = onErrorDismissed) { Text("OK") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YTD", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Platform selector + URL input + Paste
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlatformDropdown(
                    selected = uiState.platform,
                    onSelected = onPlatformChanged,
                    enabled = !isDownloading,
                    modifier = Modifier.width(130.dp),
                )

                OutlinedTextField(
                    value = uiState.url,
                    onValueChange = onUrlChanged,
                    placeholder = {
                        Text(
                            if (uiState.platform == Platform.SPOTIFY)
                                "Paste Spotify URL..."
                            else "Paste YouTube URL...",
                            maxLines = 1,
                        )
                    },
                    singleLine = true,
                    enabled = !isDownloading,
                    modifier = Modifier.weight(1f),
                    trailingIcon = {
                        IconButton(onClick = onPasteClicked, enabled = !isDownloading) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    },
                )
            }

            // Format & Quality
            if (uiState.platform != Platform.SPOTIFY) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Format:", style = MaterialTheme.typography.bodyMedium)
                    FormatDropdown(
                        selected = uiState.format,
                        onSelected = onFormatChanged,
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f),
                    )

                    if (uiState.format != DownloadFormat.AUDIO_WAV) {
                        Text("Quality:", style = MaterialTheme.typography.bodyMedium)
                        QualityDropdown(
                            selected = uiState.quality,
                            options = uiState.availableQualities,
                            onSelected = onQualityChanged,
                            enabled = !isDownloading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Output folder display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = outputDir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Download / Cancel button
            if (isDownloading) {
                Button(
                    onClick = onCancelClicked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = YtdError),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onDownloadClicked()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = uiState.url.isNotBlank(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download", fontWeight = FontWeight.Bold)
                }
            }

            // Progress bar
            val progress = (uiState.downloadState as? DownloadState.Downloading)?.progress
            if (progress != null) {
                if (progress.percent > 0f) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                val statusText = buildString {
                    if (progress.speed.isNotEmpty()) append("Speed: ${progress.speed}")
                    if (progress.sizeOrInfo.isNotEmpty()) {
                        if (isNotEmpty()) append(" | ")
                        append(progress.sizeOrInfo)
                    }
                    if (progress.eta.isNotEmpty()) {
                        if (isNotEmpty()) append(" | ")
                        append("ETA: ${progress.eta}")
                    }
                }.ifEmpty { "Downloading..." }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (uiState.downloadState == DownloadState.Idle) {
                Text(
                    text = "Ready",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // History section
            Text(
                text = "Download History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (uiState.history.isEmpty()) {
                Text(
                    text = "No downloads yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(
                        items = uiState.history,
                        key = { it.id },
                    ) { item ->
                        HistoryItemCard(
                            item = item,
                            onOpenFile = {
                                openFile(context, item.filePath)
                            },
                            onOpenFolder = {
                                openFolder(context, item.filePath)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItemCard(
    item: HistoryItem,
    onOpenFile: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenFile,
                    onLongClick = { showMenu = true },
                ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = item.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = item.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = item.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Open File") },
                onClick = {
                    showMenu = false
                    onOpenFile()
                },
            )
            DropdownMenuItem(
                text = { Text("Open Containing Folder") },
                onClick = {
                    showMenu = false
                    onOpenFolder()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformDropdown(
    selected: Platform,
    onSelected: (Platform) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (selected == Platform.YOUTUBE) "YouTube" else "Spotify",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("YouTube") },
                onClick = { onSelected(Platform.YOUTUBE); expanded = false },
            )
            DropdownMenuItem(
                text = { Text("Spotify") },
                onClick = { onSelected(Platform.SPOTIFY); expanded = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatDropdown(
    selected: DownloadFormat,
    onSelected: (DownloadFormat) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DownloadFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.displayName) },
                    onClick = { onSelected(format); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityDropdown(
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { quality ->
                DropdownMenuItem(
                    text = { Text(quality) },
                    onClick = { onSelected(quality); expanded = false },
                )
            }
        }
    }
}

private fun openFile(context: android.content.Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, getMimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        // No app to handle this file type
    }
}

private fun openFolder(context: android.content.Context, filePath: String) {
    try {
        val file = File(filePath)
        val parentDir = file.parentFile ?: return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            parentDir,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback: open with file manager
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = parentDir.toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    } catch (_: Exception) {
        // No file manager
    }
}

private fun getMimeType(file: File): String = when (file.extension.lowercase()) {
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    else -> "*/*"
}
