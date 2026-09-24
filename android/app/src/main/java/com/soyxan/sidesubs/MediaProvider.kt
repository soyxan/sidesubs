package com.soyxan.sidesubs

enum class MediaProviderType(val displayName: String) {
    PLEX("Plex"),
    JELLYFIN("Jellyfin"),
}

data class ProviderConnection(
    val provider: MediaProviderType,
    val serverId: String,
    val serverName: String,
    val baseUrl: String,
    val accessToken: String,
    val clientIdentifier: String,
    val userId: String = "",
)

interface MediaProvider {
    val providerType: MediaProviderType
    val serverName: String
    val serverAddress: String
    fun sessions(): List<PlaybackSession>
    fun subtitleTracks(mediaId: String): List<SubtitleTrack>
    fun subtitleTimeline(mediaId: String, track: SubtitleTrack): SubtitleTimeline
    fun clearSubtitleCache()
}
