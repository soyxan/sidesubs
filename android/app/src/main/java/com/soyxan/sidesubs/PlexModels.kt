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
    val mediaId: String,
    val sessionKey: String,
    val position: Double,
) {
    fun displayClient(): String = client.ifBlank { product.ifBlank { device.ifBlank { "Media client" } } }
    fun clockKey(): String = "$playerId:$mediaId:$sessionKey"
}

data class SubtitleTrack(
    val id: String,
    val source: String,
    val language: String,
    val title: String,
    val codec: String,
    val compatible: Boolean,
    val selected: Boolean,
    val providerData: Map<String, String> = emptyMap(),
) {
    fun label(): String {
        val lang = language.takeIf { it.isNotBlank() }?.uppercase()?.plus(" · ") ?: ""
        val kind = when (source) {
            "external" -> "External"
            "embedded" -> "Embedded"
            else -> source.replaceFirstChar { it.uppercase() }
        }
        return "$lang$kind · $title"
    }
}

data class SubtitleTimeline(
    val cues: List<Cue>,
)
