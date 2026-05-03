package com.elmotuisk.ytd.data.model

enum class Platform {
    YOUTUBE,
    SPOTIFY,
}

enum class DownloadFormat(val displayName: String) {
    VIDEO_MP4("Video (MP4)"),
    AUDIO_MP3("Audio Only (MP3)"),
    AUDIO_WAV("Audio Only (WAV)"),
}

data class DownloadConfig(
    val url: String,
    val platform: Platform,
    val format: DownloadFormat,
    val quality: String,
    val outputDir: String,
)

data class DownloadProgress(
    val percent: Float = 0f,
    val speed: String = "",
    val eta: String = "",
    val sizeOrInfo: String = "",
)

data class HistoryItem(
    val id: Long = 0,
    val filename: String,
    val filePath: String,
    val date: String,
    val size: String,
)

fun qualitiesForFormat(format: DownloadFormat): List<String> = when (format) {
    DownloadFormat.VIDEO_MP4 -> listOf("Best", "High (1080p)", "Medium (720p)", "Low (480p)")
    DownloadFormat.AUDIO_MP3 -> listOf("320kbps", "192kbps", "128kbps")
    DownloadFormat.AUDIO_WAV -> listOf("Default")
}
