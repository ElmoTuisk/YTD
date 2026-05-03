package com.elmotuisk.ytd.download

import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.DownloadProgress
import com.elmotuisk.ytd.data.model.HistoryItem
import com.elmotuisk.ytd.spotify.SpotifyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyDownloader @Inject constructor(
    private val spotifyClient: SpotifyClient,
    private val youtubeDownloader: YoutubeDownloader,
    private val metadataEmbedder: MetadataEmbedder,
) {
    suspend fun download(
        url: String,
        outputDir: String,
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
        onTrackComplete: suspend (HistoryItem) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val collection = spotifyClient.resolveTracks(url) { status ->
            onProgress(DownloadProgress(percent = 0f, speed = status, eta = "", sizeOrInfo = ""))
        }

        val isCollection = collection.tracks.size > 1

        // Create subfolder for playlists/albums
        val targetFolder = if (isCollection) {
            val safeName = collection.name
                .filter { it.isLetterOrDigit() || it in " -_" }
                .trim()
                .ifEmpty { "Spotify Download" }
            val folder = File(outputDir, safeName)
            folder.mkdirs()
            folder.absolutePath
        } else {
            outputDir
        }

        val total = collection.tracks.size
        for ((index, track) in collection.tracks.withIndex()) {
            onProgress(
                DownloadProgress(
                    percent = (index.toFloat() / total) * 100f,
                    speed = "Searching...",
                    eta = "Track ${index + 1}/$total",
                    sizeOrInfo = track.query,
                )
            )

            try {
                val trackProcessId = "${processId}_track_$index"
                val outputFile = youtubeDownloader.download(
                    url = "ytsearch1:${track.query}",
                    format = DownloadFormat.AUDIO_MP3,
                    quality = "320",
                    outputDir = targetFolder,
                    processId = trackProcessId,
                    onProgress = { progress ->
                        onProgress(
                            progress.copy(
                                eta = "Track ${index + 1}/$total",
                                sizeOrInfo = track.query,
                            )
                        )
                    },
                )

                // Embed Spotify metadata
                if (outputFile.exists()) {
                    metadataEmbedder.embed(
                        filePath = outputFile.absolutePath,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        artUrl = track.artUrl,
                    )

                    val dateStr = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
                    ).format(Date())

                    onTrackComplete(
                        HistoryItem(
                            filename = outputFile.name,
                            filePath = outputFile.absolutePath,
                            date = dateStr,
                            size = formatBytes(outputFile.length()),
                        )
                    )
                }
            } catch (_: Exception) {
                // Skip failed tracks, continue with next
                continue
            }
        }

        onProgress(
            DownloadProgress(
                percent = 100f,
                speed = "Done",
                eta = "Finished",
                sizeOrInfo = "$total tracks",
            )
        )
    }

    fun cancel(processId: String) {
        youtubeDownloader.cancel(processId)
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
