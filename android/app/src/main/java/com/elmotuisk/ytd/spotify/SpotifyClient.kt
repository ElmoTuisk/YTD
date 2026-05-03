package com.elmotuisk.ytd.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val SPOTIFY_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
        private const val PATHFINDER_PLAYLIST_HASH =
            "91d4c2bc3e0cd1bc672281c4f1f59f43ff55ba726ca04a45810d99bd091f3f0e"
        private const val PATHFINDER_ALBUM_HASH =
            "469874edcad37b7a379d4f22f0083a49ea3d6ae097916120d9bbe3e36ca79e9d"
        private const val PATHFINDER_TRACK_HASH =
            "ae85b52abb74d20a4c331d4143d4772c95f34757bfa8c625474b912b9055b5c0"
    }

    fun extractSpotifyIdAndType(url: String): Pair<String, String> {
        val regex = Regex("open\\.spotify\\.com/(track|album|playlist)/([a-zA-Z0-9]+)")
        val match = regex.find(url)
            ?: throw IllegalArgumentException("Invalid Spotify URL. Please use a link to a track, album, or playlist.")
        return Pair(match.groupValues[1], match.groupValues[2])
    }

    suspend fun resolveTracks(
        url: String,
        onProgress: (String) -> Unit = {},
    ): SpotifyCollection = withContext(Dispatchers.IO) {
        val (spotifyType, spotifyId) = extractSpotifyIdAndType(url)

        onProgress("Connecting to Spotify...")

        // Step 1: Get client token
        val clientToken = getClientToken()

        // Step 2: Get access token
        val accessToken = getAccessToken(clientToken)

        var tracks: List<SpotifyTrackInfo> = emptyList()
        var collectionName = "Spotify Download"

        // PRIMARY: Pathfinder GraphQL API
        if (accessToken != null) {
            onProgress("Using Pathfinder API...")
            try {
                val result = getTracksViaPathfinder(
                    accessToken, clientToken, spotifyType, spotifyId, onProgress
                )
                tracks = result.first
                collectionName = result.second
            } catch (_: Exception) {
                // Fall through to fallbacks
            }
        }

        // FALLBACK 1: Embed page scraping
        if (tracks.isEmpty()) {
            onProgress("Trying embed page...")
            try {
                val embedResult = getTracksFromEmbed(spotifyType, spotifyId)
                val embedTracks = embedResult.first
                val embedName = embedResult.second
                val embedToken = embedResult.third

                // If embed gave us a token, retry Pathfinder
                if (embedToken != null && embedToken != accessToken) {
                    try {
                        val pfResult = getTracksViaPathfinder(
                            embedToken, clientToken, spotifyType, spotifyId, onProgress
                        )
                        if (pfResult.first.isNotEmpty()) {
                            tracks = pfResult.first
                            collectionName = pfResult.second
                        }
                    } catch (_: Exception) {
                        // Use embed tracks
                    }
                }

                if (tracks.isEmpty() && embedTracks.isNotEmpty()) {
                    tracks = embedTracks
                    collectionName = embedName
                }
            } catch (_: Exception) {
                // Fall through
            }
        }

        // FALLBACK 2: oEmbed for single tracks
        if (tracks.isEmpty() && spotifyType == "track") {
            try {
                val result = getTracksViaOembed(url)
                if (result != null) {
                    tracks = listOf(result.first)
                    collectionName = result.second
                }
            } catch (_: Exception) {
                // No more fallbacks
            }
        }

        if (tracks.isEmpty()) {
            throw Exception(
                "Could not find any tracks to download.\n\n" +
                    "Possible causes:\n" +
                    "- The playlist may be private (make it public)\n" +
                    "- Spotify may be blocking requests (try again later)\n" +
                    "- The URL may be invalid"
            )
        }

        SpotifyCollection(name = collectionName, tracks = tracks)
    }

    private fun getClientToken(): String? {
        return try {
            val body = """
                {
                    "client_data": {
                        "client_version": "1.2.52.442.g0f8a4be4",
                        "client_id": "$SPOTIFY_CLIENT_ID",
                        "js_sdk_data": {
                            "device_brand": "",
                            "device_id": "",
                            "device_model": "",
                            "device_type": "",
                            "os": "",
                            "os_version": ""
                        }
                    }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://clienttoken.spotify.com/v1/clienttoken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonBody = response.body?.string() ?: return null
                val obj = json.parseToJsonElement(jsonBody).jsonObject
                obj["granted_token"]?.jsonObject?.get("token")?.jsonPrimitive?.content
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getAccessToken(clientToken: String?): String? {
        // Try visiting the main page to get cookies and extract token from HTML
        try {
            val pageRequest = Request.Builder()
                .url("https://open.spotify.com/")
                .header("Accept", "text/html,*/*")
                .build()
            val pageResponse = httpClient.newCall(pageRequest).execute()
            if (pageResponse.isSuccessful) {
                val html = pageResponse.body?.string() ?: ""
                val match = Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").find(html)
                if (match != null) return match.groupValues[1]
            }
        } catch (_: Exception) {
            // Continue to token endpoint
        }

        // Try the /api/token and /get_access_token endpoints
        val endpoints = listOf(
            "https://open.spotify.com/api/token",
            "https://open.spotify.com/get_access_token",
        )
        for (endpoint in endpoints) {
            try {
                val builder = Request.Builder()
                    .url("$endpoint?reason=transport&productType=web-player")
                    .header("Accept", "application/json")
                    .header("Referer", "https://open.spotify.com/")
                    .header("Origin", "https://open.spotify.com")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")

                if (clientToken != null) {
                    builder.header("client-token", clientToken)
                }

                val response = httpClient.newCall(builder.build()).execute()
                if (response.isSuccessful) {
                    val jsonBody = response.body?.string() ?: continue
                    val obj = json.parseToJsonElement(jsonBody).jsonObject
                    val token = obj["accessToken"]?.jsonPrimitive?.content
                    if (!token.isNullOrEmpty()) return token
                }
            } catch (_: Exception) {
                continue
            }
        }

        return null
    }

    private fun pathfinderQuery(
        accessToken: String,
        clientToken: String?,
        operation: String,
        sha256: String,
        variables: Map<String, Any>,
    ): JsonObject {
        val variablesJson = buildJsonString(variables)
        val extensionsJson =
            """{"persistedQuery":{"version":1,"sha256Hash":"$sha256"}}"""

        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api-partner.spotify.com")
            .addPathSegments("pathfinder/v1/query")
            .addQueryParameter("operationName", operation)
            .addQueryParameter("variables", variablesJson)
            .addQueryParameter("extensions", extensionsJson)

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", "https://open.spotify.com")
            .header("Referer", "https://open.spotify.com/")
            .header("App-Platform", "WebPlayer")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "cross-site")

        if (clientToken != null) {
            requestBuilder.header("client-token", clientToken)
        }

        var response = httpClient.newCall(requestBuilder.build()).execute()

        // Handle rate limiting
        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.toIntOrNull()?.coerceAtMost(30) ?: 5
            Thread.sleep(retryAfter * 1000L)
            response = httpClient.newCall(requestBuilder.build()).execute()
        }

        if (!response.isSuccessful) {
            throw Exception("Pathfinder query failed: ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response")
        return json.parseToJsonElement(body).jsonObject
    }

    private fun buildJsonString(map: Map<String, Any>): String {
        val entries = map.entries.joinToString(",") { (k, v) ->
            when (v) {
                is String -> "\"$k\":\"$v\""
                is Int -> "\"$k\":$v"
                is Long -> "\"$k\":$v"
                else -> "\"$k\":\"$v\""
            }
        }
        return "{$entries}"
    }

    private suspend fun getTracksViaPathfinder(
        accessToken: String,
        clientToken: String?,
        spotifyType: String,
        spotifyId: String,
        onProgress: (String) -> Unit,
    ): Pair<List<SpotifyTrackInfo>, String> {
        val tracks = mutableListOf<SpotifyTrackInfo>()
        var collectionName = "Spotify Download"

        when (spotifyType) {
            "playlist" -> {
                val uri = "spotify:playlist:$spotifyId"
                var offset = 0
                val limit = 100
                var total: Int? = null

                while (true) {
                    onProgress("Fetching track list... ${offset}/${total ?: "?"}")

                    val data = pathfinderQuery(
                        accessToken, clientToken,
                        "fetchPlaylistContents", PATHFINDER_PLAYLIST_HASH,
                        mapOf("uri" to uri, "offset" to offset, "limit" to limit),
                    )

                    val playlistV2 = data.obj("data")?.obj("playlistV2") ?: break

                    if (offset == 0) {
                        playlistV2.str("name")?.let { collectionName = it }
                    }

                    val content = playlistV2.obj("content") ?: break
                    if (total == null) {
                        total = content["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
                    }

                    val items = content.arr("items")
                    if (items == null || items.isEmpty()) break

                    for (item in items) {
                        try {
                            val trackData = item.jsonObject.obj("itemV2")?.obj("data") ?: continue
                            val name = trackData.str("name") ?: continue
                            if (name.isEmpty()) continue

                            val artist = trackData.obj("artists")?.arr("items")
                                ?.firstOrNull()?.jsonObject?.obj("profile")?.str("name") ?: ""

                            var artUrl = ""
                            var albumName = ""
                            val albumOfTrack = trackData.obj("albumOfTrack")
                            if (albumOfTrack != null) {
                                albumName = albumOfTrack.str("name") ?: ""
                                artUrl = albumOfTrack.obj("coverArt")?.arr("sources")
                                    ?.lastOrNull()?.jsonObject?.str("url") ?: ""
                            }

                            val query = "$name $artist".trim()
                            if (query.isNotEmpty() && tracks.none { it.query == query }) {
                                tracks.add(
                                    SpotifyTrackInfo(query, name, artist, albumName, artUrl)
                                )
                            }
                        } catch (_: Exception) {
                            continue
                        }
                    }

                    offset += limit
                    if (total != null && offset >= total) break
                    delay(300)
                }
            }

            "album" -> {
                val uri = "spotify:album:$spotifyId"
                val data = pathfinderQuery(
                    accessToken, clientToken,
                    "queryAlbumTracks", PATHFINDER_ALBUM_HASH,
                    mapOf("uri" to uri, "offset" to 0, "limit" to 300),
                )

                val albumData = data.obj("data")?.let {
                    it.obj("albumUnion") ?: it.obj("album")
                } ?: return Pair(emptyList(), collectionName)

                albumData.str("name")?.let { collectionName = it }

                val albumArtUrl = albumData.obj("coverArt")?.arr("sources")
                    ?.lastOrNull()?.jsonObject?.str("url") ?: ""

                val tracksObj = albumData.obj("tracksV2") ?: albumData.obj("tracks")
                val items = tracksObj?.arr("items") ?: return Pair(emptyList(), collectionName)

                for (item in items) {
                    try {
                        val itemObj = item.jsonObject
                        val trackData = itemObj.obj("track")
                            ?: itemObj.obj("itemV2")?.obj("data")
                            ?: itemObj

                        val name = trackData.str("name") ?: continue
                        if (name.isEmpty()) continue

                        var artist = trackData.obj("artists")?.arr("items")
                            ?.firstOrNull()?.jsonObject?.obj("profile")?.str("name") ?: ""

                        // Try flat artist list
                        if (artist.isEmpty()) {
                            val flatArtists = trackData["artists"]
                            if (flatArtists is JsonArray && flatArtists.isNotEmpty()) {
                                artist = flatArtists[0].jsonObject.str("name") ?: ""
                            }
                        }

                        val query = "$name $artist".trim()
                        if (query.isNotEmpty() && tracks.none { it.query == query }) {
                            tracks.add(
                                SpotifyTrackInfo(query, name, artist, collectionName, albumArtUrl)
                            )
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
            }

            "track" -> {
                val uri = "spotify:track:$spotifyId"
                val data = pathfinderQuery(
                    accessToken, clientToken,
                    "getTrack", PATHFINDER_TRACK_HASH,
                    mapOf("uri" to uri),
                )

                val trackData = data.obj("data")?.let {
                    it.obj("trackUnion") ?: it.obj("track")
                } ?: return Pair(emptyList(), collectionName)

                val name = trackData.str("name")
                if (!name.isNullOrEmpty()) {
                    collectionName = name

                    var artist = trackData.obj("firstArtist")?.arr("items")
                        ?.firstOrNull()?.jsonObject?.obj("profile")?.str("name") ?: ""

                    if (artist.isEmpty()) {
                        artist = trackData.obj("artists")?.arr("items")
                            ?.firstOrNull()?.jsonObject?.obj("profile")?.str("name") ?: ""
                    }

                    var artUrl = ""
                    var albumName = ""
                    val albumOfTrack = trackData.obj("albumOfTrack")
                    if (albumOfTrack != null) {
                        albumName = albumOfTrack.str("name") ?: ""
                        artUrl = albumOfTrack.obj("coverArt")?.arr("sources")
                            ?.lastOrNull()?.jsonObject?.str("url") ?: ""
                    }

                    val query = "$name $artist".trim()
                    if (query.isNotEmpty()) {
                        tracks.add(SpotifyTrackInfo(query, name, artist, albumName, artUrl))
                    }
                }
            }
        }

        return Pair(tracks, collectionName)
    }

    private fun getTracksFromEmbed(
        spotifyType: String,
        spotifyId: String,
    ): Triple<List<SpotifyTrackInfo>, String, String?> {
        val tracks = mutableListOf<SpotifyTrackInfo>()
        var collectionName = "Spotify Download"
        var embedToken: String? = null

        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$spotifyType/$spotifyId")
            .header("Accept", "text/html,*/*")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return Triple(emptyList(), collectionName, null)

        val html = response.body?.string() ?: return Triple(emptyList(), collectionName, null)

        // Search for accessToken in the page
        val tokenMatch = Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").find(html)
        if (tokenMatch != null) {
            embedToken = tokenMatch.groupValues[1]
        }

        // Parse __NEXT_DATA__ JSON
        val nextDataMatch =
            Regex("<script\\s+id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)

        if (nextDataMatch != null) {
            try {
                val data = json.parseToJsonElement(nextDataMatch.groupValues[1]).jsonObject

                // Search for token in JSON
                if (embedToken == null) {
                    embedToken = findInJson(data, setOf("accessToken", "access_token"))
                }

                // Extract tracks from entity.trackList
                val pageProps = data.obj("props")?.obj("pageProps")
                val entity = pageProps?.obj("state")?.obj("data")?.obj("entity")
                    ?: pageProps?.obj("state")?.obj("data")
                    ?: pageProps

                entity?.str("name")?.let { collectionName = it }

                val trackList = entity?.arr("trackList")
                if (trackList != null) {
                    for (t in trackList) {
                        val tObj = t.jsonObject
                        val title = tObj.str("title") ?: continue
                        if (title.isEmpty()) continue
                        val subtitle = tObj.str("subtitle") ?: ""
                        val query = "$title $subtitle".trim()

                        var artUrl = ""
                        val images = tObj.arr("images")
                        if (images != null && images.isNotEmpty()) {
                            val last = images.last()
                            artUrl = if (last is JsonPrimitive) last.content
                            else last.jsonObject.str("url") ?: ""
                        }

                        if (tracks.none { it.query == query }) {
                            tracks.add(SpotifyTrackInfo(query, title, subtitle, "", artUrl))
                        }
                    }
                }
            } catch (_: Exception) {
                // Parse failed
            }
        }

        // Fallback: search for trackList JSON in raw HTML
        if (tracks.isEmpty()) {
            val tracklistMatch =
                Regex("\"trackList\"\\s*:\\s*(\\[.*?])\\s*[,}]", RegexOption.DOT_MATCHES_ALL)
                    .find(html)
            if (tracklistMatch != null) {
                try {
                    val arr = json.parseToJsonElement(tracklistMatch.groupValues[1]).jsonArray
                    for (t in arr) {
                        val tObj = t.jsonObject
                        val title = tObj.str("title") ?: continue
                        if (title.isEmpty()) continue
                        val subtitle = tObj.str("subtitle") ?: ""
                        val query = "$title $subtitle".trim()
                        if (tracks.none { it.query == query }) {
                            tracks.add(SpotifyTrackInfo(query, title, subtitle, "", ""))
                        }
                    }
                } catch (_: Exception) {
                    // Parse failed
                }
            }
        }

        // Get name from meta tags
        if (collectionName == "Spotify Download") {
            val ogTitle =
                Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]*)\"").find(html)
            if (ogTitle != null) {
                collectionName = ogTitle.groupValues[1]
            }
        }

        return Triple(tracks, collectionName, embedToken)
    }

    private fun getTracksViaOembed(url: String): Pair<SpotifyTrackInfo, String>? {
        val request = Request.Builder()
            .url("https://open.spotify.com/oembed?url=$url")
            .header("Accept", "application/json")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        val title = obj.str("title") ?: return null
        if (title.isEmpty()) return null

        val artUrl = obj.str("thumbnail_url") ?: ""
        return Pair(
            SpotifyTrackInfo(query = title, title = title, artist = "", album = "", artUrl = artUrl),
            title,
        )
    }

    private fun findInJson(
        element: JsonElement,
        keys: Set<String>,
        depth: Int = 0,
    ): String? {
        if (depth > 10) return null
        when (element) {
            is JsonObject -> {
                for ((k, v) in element) {
                    if (k in keys && v is JsonPrimitive && v.isString && v.content.length > 50) {
                        return v.content
                    }
                    findInJson(v, keys, depth + 1)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findInJson(item, keys, depth + 1)?.let { return it }
                }
            }
            else -> {}
        }
        return null
    }

    // JSON helper extensions
    private fun JsonElement.obj(key: String): JsonObject? =
        (this as? JsonObject)?.get(key)?.let { if (it is JsonObject) it else null }

    private fun JsonElement.arr(key: String): JsonArray? =
        (this as? JsonObject)?.get(key)?.let { if (it is JsonArray) it else null }

    private fun JsonElement.str(key: String): String? =
        (this as? JsonObject)?.get(key)?.let {
            if (it is JsonPrimitive && it.isString) it.content else null
        }

    private fun JsonObject.obj(key: String): JsonObject? =
        this[key]?.let { if (it is JsonObject) it else null }

    private fun JsonObject.arr(key: String): JsonArray? =
        this[key]?.let { if (it is JsonArray) it else null }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { if (it is JsonPrimitive) it.content else null }
}
