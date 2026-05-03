package com.elmotuisk.ytd.spotify

data class SpotifyTrackInfo(
    val query: String,
    val title: String,
    val artist: String,
    val album: String,
    val artUrl: String,
)

data class SpotifyCollection(
    val name: String,
    val tracks: List<SpotifyTrackInfo>,
)
