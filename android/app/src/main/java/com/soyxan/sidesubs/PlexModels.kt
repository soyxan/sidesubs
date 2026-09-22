package com.soyxan.sidesubs

data class Cue(
    val start: Double,
    val end: Double,
    val text: String,
)

data class PlaybackSession(
    val playerId: String,
    val client: String,
    val product: String,
    val device: String,
    val state: String,
    val title: String,
    val ratingKey: String,
    val sessionKey: String,
    val position: Double,
) {
    fun displayClient(): String = client.ifBlank { product.ifBlank { device.ifBlank { "Plex" } } }
    fun clockKey(): String = "$playerId:$ratingKey:$sessionKey"
}

data class SubtitleTrack(
    val streamId: Int,
    val partId: Int,
    val source: String,
    val language: String,
    val title: String,
    val codec: String,
    val key: String,
    val compatible: Boolean,
    val selected: Boolean,
) {
    val id: String = "plex:$streamId"

    fun label(): String {
        val lang = language.takeIf { it.isNotBlank() }?.uppercase()?.plus(" · ") ?: ""
        val kind = if (source == "external") "External" else "MKV"
        return "$lang$kind · $title"
    }
}

data class SubtitleTimeline(
    val cues: List<Cue>,
)
