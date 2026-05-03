package com.elmotuisk.ytd.download

import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataEmbedder @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun embed(
        filePath: String,
        title: String = "",
        artist: String = "",
        album: String = "",
        artUrl: String = "",
    ) = withContext(Dispatchers.IO) {
        try {
            val mp3File = Mp3File(filePath)
            val tag = if (mp3File.hasId3v2Tag()) mp3File.id3v2Tag else ID3v24Tag()

            if (title.isNotEmpty()) tag.title = title
            if (artist.isNotEmpty()) tag.artist = artist
            if (album.isNotEmpty()) tag.album = album

            if (artUrl.isNotEmpty()) {
                try {
                    val request = Request.Builder().url(artUrl).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val mime = response.header("Content-Type") ?: "image/jpeg"
                            tag.setAlbumImage(bytes, mime)
                        }
                    }
                } catch (_: Exception) {
                    // Failed to download art, continue without it
                }
            }

            mp3File.id3v2Tag = tag

            // mp3agic requires writing to a new file then renaming
            val tempPath = "$filePath.tmp"
            mp3File.save(tempPath)
            File(tempPath).renameTo(File(filePath))
        } catch (_: Exception) {
            // Metadata embedding is best-effort
        }
    }
}
