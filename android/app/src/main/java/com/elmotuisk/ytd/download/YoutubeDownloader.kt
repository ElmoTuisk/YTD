package com.elmotuisk.ytd.download

import android.content.Context
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.DownloadProgress
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YoutubeDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun download(
        url: String,
        format: DownloadFormat,
        quality: String,
        outputDir: String,
        processId: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
        request.addOption("-o", "$outputDir/%(title)s.%(ext)s")
        request.addOption("--no-mtime")

        when (format) {
            DownloadFormat.VIDEO_MP4 -> {
                request.addOption("--merge-output-format", "mp4")
                val formatStr = when (quality) {
                    "Best" -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
                    "High (1080p)" -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best"
                    "Medium (720p)" -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best"
                    "Low (480p)" -> "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best"
                    else -> "best"
                }
                request.addOption("-f", formatStr)
            }

            DownloadFormat.AUDIO_MP3 -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                val qualityVal = quality.replace("kbps", "")
                request.addOption("--audio-quality", qualityVal)
                request.addOption("--embed-thumbnail")
                request.addOption("--embed-metadata")
            }

            DownloadFormat.AUDIO_WAV -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "wav")
            }
        }

        val response = YoutubeDL.getInstance().execute(
            request,
            processId,
        ) { progress, etaInSeconds, _ ->
            onProgress(
                DownloadProgress(
                    percent = progress,
                    speed = "",
                    eta = if (etaInSeconds > 0) "${etaInSeconds}s" else "",
                    sizeOrInfo = "",
                )
            )
        }

        // Find the output file - parse stdout for the filename
        findOutputFile(outputDir, format, response.out ?: "")
    }

    fun cancel(processId: String) {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    private fun findOutputFile(
        outputDir: String,
        format: DownloadFormat,
        stdout: String,
    ): File {
        // Try to find the destination file from yt-dlp output
        val destPatterns = listOf(
            Regex("\\[Merger\\] Merging formats into \"(.+?)\""),
            Regex("\\[ExtractAudio\\] Destination: (.+)"),
            Regex("Destination: (.+)"),
            Regex("\\[download\\] (.+?) has already been downloaded"),
            Regex("\\[EmbedThumbnail\\] (?:mutagen|ffmpeg): Adding thumbnail to \"(.+?)\""),
        )
        for (pattern in destPatterns) {
            val match = pattern.find(stdout)
            if (match != null) {
                val file = File(match.groupValues[1])
                if (file.exists()) return file
            }
        }

        // Fallback: find the most recently modified file in outputDir matching the format
        val extension = when (format) {
            DownloadFormat.VIDEO_MP4 -> "mp4"
            DownloadFormat.AUDIO_MP3 -> "mp3"
            DownloadFormat.AUDIO_WAV -> "wav"
        }

        val dir = File(outputDir)
        return dir.listFiles()
            ?.filter { it.extension.equals(extension, ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?: throw Exception("Download completed but output file not found")
    }
}
