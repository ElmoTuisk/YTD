package com.elmotuisk.ytd.ui.download

import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.HistoryItem
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.download.DownloadState

data class DownloadScreenState(
    val url: String = "",
    val platform: Platform = Platform.YOUTUBE,
    val format: DownloadFormat = DownloadFormat.VIDEO_MP4,
    val quality: String = "Best",
    val availableQualities: List<String> = listOf("Best", "High (1080p)", "Medium (720p)", "Low (480p)"),
    val downloadState: DownloadState = DownloadState.Idle,
    val history: List<HistoryItem> = emptyList(),
    val outputFolderDisplay: String = "Default (App Storage)",
    val hasCustomFolder: Boolean = false,
)
