package com.elmotuisk.ytd.ui.download

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoFile
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.HistoryItem
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.download.DownloadState
import com.elmotuisk.ytd.ui.theme.YtdError
import com.elmotuisk.ytd.ui.theme.YtdPrimary
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
        onUrlChanged = viewModel::onUrlChanged,
        onPlatformChanged = viewModel::onPlatformChanged,
        onFormatChanged = viewModel::onFormatChanged,
        onQualityChanged = viewModel::onQualityChanged,
        onPasteClicked = viewModel::onPasteClicked,
        onDownloadClicked = viewModel::onDownloadClicked,
        onCancelClicked = viewModel::onCancelClicked,
        onDownloadCompleted = viewModel::onDownloadCompleted,
        onErrorDismissed = viewModel::onErrorDismissed,
        onFolderSelected = viewModel::onFolderSelected,
        onResetFolder = viewModel::onResetFolder,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    uiState: DownloadScreenState,
    onUrlChanged: (String) -> Unit,
    onPlatformChanged: (Platform) -> Unit,
    onFormatChanged: (DownloadFormat) -> Unit,
    onQualityChanged: (String) -> Unit,
    onPasteClicked: () -> Unit,
    onDownloadClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onDownloadCompleted: () -> Unit,
    onErrorDismissed: () -> Unit,
    onFolderSelected: (Uri) -> Unit,
    onResetFolder: () -> Unit,
) {
    val context = LocalContext.current
    var notificationPermissionGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        onDownloadClicked()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onFolderSelected(uri)
    }

    val isDownloading = uiState.downloadState is DownloadState.Downloading

    LaunchedEffect(uiState.downloadState) {
        if (uiState.downloadState is DownloadState.Completed) {
            onDownloadCompleted()
        }
    }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
    ) {
        // App title
        Text(
            text = "YTD",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = YtdPrimary,
        )
        Text(
            text = "YouTube & Spotify Downloader",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Platform toggle - full width segmented button
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = uiState.platform == Platform.YOUTUBE,
                onClick = { onPlatformChanged(Platform.YOUTUBE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !isDownloading,
            ) {
                Text("YouTube")
            }
            SegmentedButton(
                selected = uiState.platform == Platform.SPOTIFY,
                onClick = { onPlatformChanged(Platform.SPOTIFY) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !isDownloading,
            ) {
                Text("Spotify")
            }
        }

        Spacer(Modifier.height(16.dp))

        // URL input - full width with paste button
        OutlinedTextField(
            value = uiState.url,
            onValueChange = onUrlChanged,
            placeholder = {
                Text(
                    if (uiState.platform == Platform.SPOTIFY)
                        "Paste Spotify URL here..."
                    else "Paste YouTube URL here...",
                )
            },
            singleLine = true,
            enabled = !isDownloading,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (uiState.url.isNotEmpty() && !isDownloading) {
                    IconButton(onClick = { onUrlChanged("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            leadingIcon = {
                IconButton(onClick = onPasteClicked, enabled = !isDownloading) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                }
            },
        )

        Spacer(Modifier.height(16.dp))

        // Format & Quality - stacked for phone layout
        if (uiState.platform != Platform.SPOTIFY) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FormatDropdown(
                    selected = uiState.format,
                    onSelected = onFormatChanged,
                    enabled = !isDownloading,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.format != DownloadFormat.AUDIO_WAV) {
                    QualityDropdown(
                        selected = uiState.quality,
                        options = uiState.availableQualities,
                        onSelected = onQualityChanged,
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Output folder picker
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = YtdPrimary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Save to",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.outputFolderDisplay,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    enabled = !isDownloading,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Change", fontSize = 13.sp)
                }
                if (uiState.hasCustomFolder) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onResetFolder,
                        enabled = !isDownloading,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Reset to default",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Download / Cancel button
        if (isDownloading) {
            Button(
                onClick = onCancelClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = YtdError),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Cancel Download", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                    .height(56.dp),
                enabled = uiState.url.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // Progress section
        val progress = (uiState.downloadState as? DownloadState.Downloading)?.progress
        if (progress != null) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { if (progress.percent > 0f) progress.percent / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            if (progress.percent <= 0f) {
                // Overlay indeterminate on top
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
            Spacer(Modifier.height(8.dp))
            val statusText = buildString {
                if (progress.sizeOrInfo.isNotEmpty()) append(progress.sizeOrInfo)
                if (progress.speed.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(progress.speed)
                }
                if (progress.eta.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(progress.eta)
                }
            }.ifEmpty { "Downloading..." }

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(20.dp))

        // History section - fills remaining space
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Download History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (uiState.history.isNotEmpty()) {
                Text(
                    text = "${uiState.history.size} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (uiState.history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No downloads yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(
                    items = uiState.history,
                    key = { it.id },
                ) { item ->
                    HistoryItemCard(
                        item = item,
                        onOpenFile = { openFile(context, item.filePath) },
                        onOpenFolder = { openFolder(context, item.filePath) },
                    )
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

    val icon = when {
        item.filename.endsWith(".mp4", ignoreCase = true) -> Icons.Default.VideoFile
        else -> Icons.Default.MusicNote
    }

    Box {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenFile,
                    onLongClick = { showMenu = true },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // File type icon
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(YtdPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = YtdPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.size}  ·  ${item.date}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
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
                onClick = { showMenu = false; onOpenFile() },
            )
            DropdownMenuItem(
                text = { Text("Open Containing Folder") },
                onClick = { showMenu = false; onOpenFolder() },
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
            label = { Text("Format") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
            label = { Text("Quality") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
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
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = parentDir.toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    } catch (_: Exception) { }
}

private fun getMimeType(file: File): String = when (file.extension.lowercase()) {
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    else -> "*/*"
}
