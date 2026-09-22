package com.soyxan.sidesubs

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class PlexClient(
    baseUrl: String,
    token: String,
    private val clientIdentifier: String,
    override val serverName: String,
) : MediaProvider {
    override val providerType = MediaProviderType.PLEX
    val serverUrl: String = normalizeBaseUrl(baseUrl)
    private val token = token.trim()

    private val subtitleCache = object : LinkedHashMap<String, SubtitleTimeline>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SubtitleTimeline>?
        ): Boolean = size > 32
    }

    init {
        require(serverUrl.isNotBlank()) { "Plex URL is required" }
        require(this.token.isNotBlank()) { "Plex token is required" }
    }

    override fun sessions(): List<PlaybackSession> {
        val root = getJson("/status/sessions")
        val metadata = root.optJSONObject("MediaContainer")?.optJSONArray("Metadata") ?: return emptyList()

        return buildList {
            for (i in 0 until metadata.length()) {
                val item = metadata.optJSONObject(i) ?: continue
                val player = firstObject(item, "Player") ?: continue

                val playerId = firstNonEmpty(
                    player.optString("machineIdentifier"),
                    player.optString("uuid"),
                    "fallback:${player.optString("title")}|${player.optString("product")}",
                )
                val show = item.optString("grandparentTitle")
                val title = item.optString("title")
                val season = item.optInt("parentIndex", -1)
                val episode = item.optInt("index", -1)
                val displayTitle = if (show.isNotBlank()) {
                    val code = if (season >= 0 && episode >= 0) {
                        String.format(Locale.US, " · S%02dE%02d", season, episode)
                    } else {
                        ""
                    }
                    "$show$code · $title"
                } else {
                    title.ifBlank { "Plex" }
                }

                add(
                    PlaybackSession(
                        playerId = playerId,
                        client = player.optString("title"),
                        product = player.optString("product"),
                        device = player.optString("device"),
                        state = player.optString("state", "unknown"),
                        title = displayTitle,
                        mediaId = stringValue(item, "ratingKey"),
                        sessionKey = stringValue(item, "sessionKey"),
                        position = item.optDouble("viewOffset", 0.0) / 1000.0,
                    )
                )
            }
        }
    }

    override fun subtitleTracks(mediaId: String): List<SubtitleTrack> = metadata(mediaId).tracks

    override fun subtitleTimeline(mediaId: String, track: SubtitleTrack): SubtitleTimeline {
        val cacheKey = "$mediaId:${track.id}"
        synchronized(subtitleCache) {
            subtitleCache[cacheKey]?.let { return it }
        }

        val timeline = if (track.source == "external" && track.providerData["key"].orEmpty().isNotBlank()) {
            fetchExternal(track)
        } else {
            fetchEmbedded(mediaId, track)
        }

        synchronized(subtitleCache) {
            subtitleCache[cacheKey] = timeline
        }
        return timeline
    }

    override fun clearSubtitleCache() = synchronized(subtitleCache) {
        subtitleCache.clear()
    }

    private fun fetchExternal(track: SubtitleTrack): SubtitleTimeline {
        val payload = request(
            method = "GET",
            path = track.providerData["key"].orEmpty(),
            params = mapOf("encoding" to "utf-8", "format" to "srt"),
            readTimeoutMs = READ_TIMEOUT_MS,
            accept = "*/*",
        )
        val cues = SubtitleParser.parse(payload.toString(StandardCharsets.UTF_8))
        check(cues.isNotEmpty()) { "Plex returned an empty subtitle" }
        return SubtitleTimeline(cues)
    }

    private fun fetchEmbedded(mediaId: String, track: SubtitleTrack): SubtitleTimeline {
        val oldStreamId = metadata(mediaId).tracks.firstOrNull { it.selected }?.streamId ?: 0
        val transcodeSession = UUID.randomUUID().toString().replace("-", "").take(24)
        val playbackSession = UUID.randomUUID().toString().replace("-", "")

        val common = linkedMapOf(
            "hasMDE" to "1",
            "path" to "/library/metadata/$mediaId",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "fastSeek" to "1",
            "directPlay" to "1",
            "directStream" to "1",
            "directStreamAudio" to "1",
            "subtitleSize" to "100",
            "audioBoost" to "100",
            "videoQuality" to "100",
            "videoResolution" to "4096x2160",
            "location" to "lan",
            "mediaBufferSize" to "50000",
            "session" to transcodeSession,
            "subtitles" to "sidecar",
            "subtitleStreamID" to (track.providerData["streamId"]?.toIntOrNull() ?: error("Missing Plex stream id")).toString(),
        )

        val changedSelection = oldStreamId != (track.providerData["streamId"]?.toIntOrNull() ?: error("Missing Plex stream id"))
        if (changedSelection) selectSubtitle((track.providerData["partId"]?.toIntOrNull() ?: error("Missing Plex part id")), (track.providerData["streamId"]?.toIntOrNull() ?: error("Missing Plex stream id")))

        try {
            request(
                method = "GET",
                path = "/video/:/transcode/universal/decision",
                params = LinkedHashMap(common).apply { put("protocol", "hls") },
                readTimeoutMs = READ_TIMEOUT_MS,
                accept = "application/json",
                playbackSessionId = playbackSession,
            )

            val payload = request(
                method = "GET",
                path = "/subtitles/:/transcode/universal/start",
                params = LinkedHashMap(common).apply {
                    put("protocol", "http")
                    put("copyts", "1")
                    put("offset", "0")
                },
                readTimeoutMs = SUBTITLE_TIMEOUT_MS,
                accept = "application/json",
                playbackSessionId = playbackSession,
            )

            val cues = SubtitleParser.parse(payload.toString(StandardCharsets.UTF_8))
            check(cues.isNotEmpty()) { "Plex returned a subtitle with no parseable cues" }
            return SubtitleTimeline(cues)
        } finally {
            if (changedSelection) runCatching { selectSubtitle((track.providerData["partId"]?.toIntOrNull() ?: error("Missing Plex part id")), oldStreamId) }
        }
    }

    private fun selectSubtitle(partId: Int, streamId: Int) {
        request(
            method = "PUT",
            path = "/library/parts/$partId",
            params = mapOf("allParts" to "1", "subtitleStreamID" to streamId.toString()),
            readTimeoutMs = READ_TIMEOUT_MS,
            accept = "*/*",
        )
    }

    private fun metadata(mediaId: String): MetadataData {
        val root = getJson("/library/metadata/$mediaId")
        val item = root.optJSONObject("MediaContainer")
            ?.optJSONArray("Metadata")
            ?.optJSONObject(0)
            ?: error("Plex metadata not found")

        val part = item.optJSONArray("Media")
            ?.optJSONObject(0)
            ?.optJSONArray("Part")
            ?.optJSONObject(0)
            ?: error("Plex media item has no parts")

        val partId = intValue(part, "id", 0)
        val streams = part.optJSONArray("Stream") ?: JSONArray()

        val tracks = buildList {
            for (i in 0 until streams.length()) {
                val stream = streams.optJSONObject(i) ?: continue
                if (intValue(stream, "streamType", 0) != 3) continue

                val streamId = intValue(stream, "id", 0)
                val codec = stream.optString("codec").lowercase(Locale.US)
                val key = stream.optString("key")
                val language = normalizeLanguage(
                    firstNonEmpty(stream.optString("languageCode"), stream.optString("language"))
                )
                val title = firstNonEmpty(
                    stream.optString("title"),
                    stream.optString("extendedDisplayTitle"),
                    stream.optString("displayTitle"),
                    if (language.isBlank()) "Subtitle $streamId" else language.uppercase(Locale.US),
                )

                add(
                    SubtitleTrack(
                        id = "plex:$streamId",
                        source = if (key.isBlank()) "embedded" else "external",
                        language = language,
                        title = title,
                        codec = codec,
                        compatible = codec in TEXT_SUBTITLE_CODECS,
                        selected = intValue(stream, "selected", 0) == 1 || stream.optBoolean("selected", false),
                        providerData = mapOf(
                            "streamId" to streamId.toString(),
                            "partId" to partId.toString(),
                            "key" to key,
                        ),
                    )
                )
            }
        }
        return MetadataData(tracks)
    }

    private fun getJson(path: String, params: Map<String, String>? = null): JSONObject =
        JSONObject(request("GET", path, params, READ_TIMEOUT_MS, "application/json").toString(StandardCharsets.UTF_8))

    private fun request(
        method: String,
        path: String,
        params: Map<String, String>?,
        readTimeoutMs: Int,
        accept: String,
        playbackSessionId: String? = null,
    ): ByteArray {
        val builder = Uri.parse(if (path.startsWith("http")) path else serverUrl + path).buildUpon()
        params?.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        builder.appendQueryParameter("X-Plex-Token", token)

        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("X-Plex-Token", token)
        connection.setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
        connection.setRequestProperty("X-Plex-Product", "SideSubs")
        connection.setRequestProperty("X-Plex-Version", "1")

        if (playbackSessionId != null) {
            connection.setRequestProperty("X-Plex-Platform", "Chrome")
            connection.setRequestProperty("X-Plex-Device", "SideSubs")
            connection.setRequestProperty("X-Plex-Client-Profile-Name", "Web")
            connection.setRequestProperty("X-Plex-Session-Identifier", playbackSessionId)
        } else {
            connection.setRequestProperty("X-Plex-Platform", "Android")
            connection.setRequestProperty("X-Plex-Device", "SideSubs")
        }
        connection.setRequestProperty("Accept", accept)

        val status = connection.responseCode
        val payload = readFully(if (status >= 400) connection.errorStream else connection.inputStream)
        connection.disconnect()

        if (status !in 200..299) {
            var detail = payload.toString(StandardCharsets.UTF_8)
            if (detail.length > 300) detail = detail.take(300)
            val requestPath = if (path.startsWith("http")) Uri.parse(path).path else path
            error("Plex HTTP $status on $requestPath${if (detail.isBlank()) "" else ": $detail"}")
        }
        return payload
    }

    private fun readFully(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    private fun firstObject(obj: JSONObject, key: String): JSONObject? = when (val value = obj.opt(key)) {
        is JSONObject -> value
        is JSONArray -> value.optJSONObject(0)
        else -> null
    }

    private fun normalizeBaseUrl(value: String): String {
        var url = value.trim()
        if (url.isBlank()) return ""
        if (!url.matches(Regex("(?i)^https?://.*"))) url = "http://$url"

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return ""
        val host = uri.host ?: return ""
        if (scheme != "http" && scheme != "https") return ""

        val authority = if (uri.port >= 0) "$host:${uri.port}" else host
        return Uri.Builder().scheme(scheme).encodedAuthority(authority).build().toString()
    }

    private fun normalizeLanguage(value: String): String {
        val lang = value.trim().lowercase(Locale.US)
        return when {
            lang.startsWith("es") || lang.startsWith("spa") -> "es"
            lang.startsWith("en") || lang.startsWith("eng") -> "en"
            lang.startsWith("fr") || lang.startsWith("fre") || lang.startsWith("fra") -> "fr"
            lang.startsWith("de") || lang.startsWith("ger") || lang.startsWith("deu") -> "de"
            lang.startsWith("it") || lang.startsWith("ita") -> "it"
            lang.startsWith("pt") || lang.startsWith("por") -> "pt"
            lang.length > 2 -> lang.take(2)
            else -> lang
        }
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun stringValue(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return if (value == null || value == JSONObject.NULL) "" else value.toString()
    }

    private fun intValue(obj: JSONObject, key: String, fallback: Int): Int {
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toInt()
            null -> fallback
            else -> value.toString().toIntOrNull() ?: fallback
        }
    }

    private data class MetadataData(val tracks: List<SubtitleTrack>)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val SUBTITLE_TIMEOUT_MS = 120_000
        val TEXT_SUBTITLE_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text")
    }
}
