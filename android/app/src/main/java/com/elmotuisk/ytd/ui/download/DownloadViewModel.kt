package com.elmotuisk.ytd.ui.download

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elmotuisk.ytd.data.model.DownloadConfig
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.data.model.qualitiesForFormat
import com.elmotuisk.ytd.data.repository.DownloadRepository
import com.elmotuisk.ytd.data.repository.PreferencesRepository
import com.elmotuisk.ytd.download.DownloadState
import com.elmotuisk.ytd.download.YtdDownloadManager
import com.elmotuisk.ytd.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val application: Application,
    private val downloadManager: YtdDownloadManager,
    private val downloadRepository: DownloadRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private data class LocalState(
        val url: String = "",
        val platform: Platform = Platform.YOUTUBE,
        val format: DownloadFormat = DownloadFormat.VIDEO_MP4,
        val quality: String = "Best",
        val outputFolderUri: String = "",
        val prefsLoaded: Boolean = false,
    )

    private val localState = MutableStateFlow(LocalState())

    val uiState: StateFlow<DownloadScreenState> = combine(
        localState,
        downloadRepository.getHistory(),
        downloadManager.downloadState,
        preferencesRepository.preferences,
    ) { local, history, dlState, prefs ->
        val state = if (!local.prefsLoaded) {
            local.copy(
                platform = prefs.lastPlatform,
                format = if (prefs.lastPlatform == Platform.SPOTIFY) DownloadFormat.AUDIO_MP3 else prefs.lastFormat,
                quality = prefs.lastQuality,
                outputFolderUri = prefs.outputFolderUri,
                prefsLoaded = true,
            )
        } else {
            local
        }

        if (!local.prefsLoaded) {
            localState.value = state
        }

        val qualities = qualitiesForFormat(state.format)

        val folderDisplay = if (state.outputFolderUri.isNotEmpty()) {
            getFolderDisplayName(state.outputFolderUri)
        } else {
            "Default (App Storage)"
        }

        DownloadScreenState(
            url = state.url,
            platform = state.platform,
            format = state.format,
            quality = if (state.quality in qualities) state.quality else qualities.first(),
            availableQualities = qualities,
            downloadState = dlState,
            history = history,
            outputFolderDisplay = folderDisplay,
            hasCustomFolder = state.outputFolderUri.isNotEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DownloadScreenState(),
    )

    fun onUrlChanged(url: String) {
        localState.update { it.copy(url = url) }
        if ("spotify.com" in url) {
            onPlatformChanged(Platform.SPOTIFY)
        } else if ("youtube.com" in url || "youtu.be" in url) {
            onPlatformChanged(Platform.YOUTUBE)
        }
    }

    fun onPlatformChanged(platform: Platform) {
        localState.update { state ->
            if (platform == Platform.SPOTIFY) {
                state.copy(
                    platform = platform,
                    format = DownloadFormat.AUDIO_MP3,
                    quality = "320kbps",
                )
            } else {
                state.copy(platform = platform)
            }
        }
        viewModelScope.launch { preferencesRepository.savePlatform(platform) }
    }

    fun onFormatChanged(format: DownloadFormat) {
        val qualities = qualitiesForFormat(format)
        localState.update { it.copy(format = format, quality = qualities.first()) }
        viewModelScope.launch { preferencesRepository.saveFormat(format) }
    }

    fun onQualityChanged(quality: String) {
        localState.update { it.copy(quality = quality) }
        viewModelScope.launch { preferencesRepository.saveQuality(quality) }
    }

    fun onFolderSelected(uri: Uri) {
        // Take persistable permission so we can access this folder after app restart
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        application.contentResolver.takePersistableUriPermission(uri, flags)

        val uriString = uri.toString()
        localState.update { it.copy(outputFolderUri = uriString) }
        viewModelScope.launch { preferencesRepository.saveOutputFolderUri(uriString) }
    }

    fun onResetFolder() {
        localState.update { it.copy(outputFolderUri = "") }
        viewModelScope.launch { preferencesRepository.saveOutputFolderUri("") }
    }

    fun onPasteClicked() {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            onUrlChanged(text)
        }
    }

    fun onDownloadClicked() {
        val state = localState.value
        if (state.url.isBlank()) return

        val outputDir = resolveOutputDir()

        val config = DownloadConfig(
            url = state.url,
            platform = state.platform,
            format = state.format,
            quality = state.quality,
            outputDir = outputDir,
        )

        DownloadService.startDownload(application, config)
    }

    fun onCancelClicked() {
        DownloadService.cancelDownload(application)
    }

    fun onDownloadCompleted() {
        localState.update { it.copy(url = "") }
        downloadManager.resetState()
    }

    fun onErrorDismissed() {
        downloadManager.resetState()
    }

    fun handleShareIntent(text: String) {
        onUrlChanged(text)
    }

    private fun resolveOutputDir(): String {
        val folderUri = localState.value.outputFolderUri
        if (folderUri.isNotEmpty()) {
            // Convert SAF tree URI to a file path if possible
            val uri = Uri.parse(folderUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // primary: means internal storage
            if (docId.startsWith("primary:")) {
                val relativePath = docId.removePrefix("primary:")
                val path = File(
                    Environment.getExternalStorageDirectory(),
                    relativePath,
                )
                path.mkdirs()
                return path.absolutePath
            }
        }
        // Default: app's external files directory
        val dir = File(application.getExternalFilesDir(null), "YTD")
        dir.mkdirs()
        return dir.absolutePath
    }

    private fun getFolderDisplayName(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val path = docId.removePrefix("primary:")
                if (path.isEmpty()) "Internal Storage" else path
            } else {
                docId
            }
        } catch (_: Exception) {
            "Custom Folder"
        }
    }
}
