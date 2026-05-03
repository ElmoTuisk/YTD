package com.elmotuisk.ytd.download

import com.elmotuisk.ytd.data.model.DownloadConfig
import com.elmotuisk.ytd.data.model.DownloadProgress
import com.elmotuisk.ytd.data.model.HistoryItem
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.data.repository.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: DownloadProgress) : DownloadState
    data class Completed(val item: HistoryItem) : DownloadState
    data class Error(val message: String) : DownloadState
}

@Singleton
class YtdDownloadManager @Inject constructor(
    private val youtubeDownloader: YoutubeDownloader,
    private val spotifyDownloader: SpotifyDownloader,
    private val downloadRepository: DownloadRepository,
) {
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var currentJob: Job? = null
    private var currentProcessId: String = ""

    fun startDownload(config: DownloadConfig, scope: CoroutineScope) {
        if (currentJob?.isActive == true) return

        currentProcessId = UUID.randomUUID().toString()
        _downloadState.value = DownloadState.Downloading(DownloadProgress())

        currentJob = scope.launch {
            try {
                // Ensure output directory exists
                File(config.outputDir).mkdirs()

                val isSpotify = config.platform == Platform.SPOTIFY ||
                    "spotify.com" in config.url

                if (isSpotify) {
                    spotifyDownloader.download(
                        url = config.url,
                        outputDir = config.outputDir,
                        processId = currentProcessId,
                        onProgress = { progress ->
                            _downloadState.value = DownloadState.Downloading(progress)
                        },
                        onTrackComplete = { item ->
                            downloadRepository.addHistoryEntry(item)
                        },
                    )
                    // For Spotify, individual tracks are added to history in onTrackComplete.
                    // Signal completion with the last track info.
                    _downloadState.value = DownloadState.Completed(
                        HistoryItem(
                            filename = "Spotify download complete",
                            filePath = config.outputDir,
                            date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                .format(Date()),
                            size = "",
                        )
                    )
                } else {
                    val outputFile = youtubeDownloader.download(
                        url = config.url,
                        format = config.format,
                        quality = config.quality,
                        outputDir = config.outputDir,
                        processId = currentProcessId,
                        onProgress = { progress ->
                            _downloadState.value = DownloadState.Downloading(progress)
                        },
                    )

                    val dateStr = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                    ).format(Date())

                    val item = HistoryItem(
                        filename = outputFile.name,
                        filePath = outputFile.absolutePath,
                        date = dateStr,
                        size = formatBytes(outputFile.length()),
                    )

                    downloadRepository.addHistoryEntry(item)
                    _downloadState.value = DownloadState.Completed(item)
                }
            } catch (e: Exception) {
                val message = e.message ?: "Unknown error"
                _downloadState.value = if ("cancel" in message.lowercase()) {
                    DownloadState.Error("Download cancelled.")
                } else {
                    DownloadState.Error("Error: $message")
                }
            }
        }
    }

    fun cancel() {
        try {
            youtubeDownloader.cancel(currentProcessId)
            // Also cancel any active track downloads
            spotifyDownloader.cancel(currentProcessId)
        } catch (_: Exception) {
            // Process might already be dead
        }
        currentJob?.cancel()
        _downloadState.value = DownloadState.Error("Download cancelled.")
    }

    fun resetState() {
        if (_downloadState.value !is DownloadState.Downloading) {
            _downloadState.value = DownloadState.Idle
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        for (unit in units) {
            if (value < 1024.0) return "%.2f %s".format(value, unit)
            value /= 1024.0
        }
        return "%.2f PB".format(value)
    }
}
