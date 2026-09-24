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
import java.util.concurrent.ConcurrentHashMap

class JellyfinClient(
    baseUrl: String,
    token: String,
    private val userId: String,
    private val deviceId: String,
    override val serverName: String,
    private val diagnostics: DiagnosticLog,
) : MediaProvider {
    override val providerType = MediaProviderType.JELLYFIN
    private val serverUrl = baseUrl.trimEnd('/')
    override val serverAddress: String = serverUrl
    private val accessToken = token.trim()
    private val mediaSourceIds = ConcurrentHashMap<String, String>()
    private val selectedSubtitleIndexes = ConcurrentHashMap<String, Int>()

    private val subtitleCache = object : LinkedHashMap<String, SubtitleTimeline>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SubtitleTimeline>?
        ): Boolean = size > 32
    }

    init {
        require(serverUrl.isNotBlank()) { "Jellyfin URL is required" }
        require(accessToken.isNotBlank()) { "Jellyfin token is required" }
        require(userId.isNotBlank()) { "Jellyfin user id is required" }
    }

    override fun sessions(): List<PlaybackSession> {
        val array = getJsonArray("/Sessions")
        return buildList {
            for (i in 0 until array.length()) {
                val session = array.optJSONObject(i) ?: continue
                val item = session.optJSONObject("NowPlayingItem") ?: continue
                val playState = session.optJSONObject("PlayState") ?: JSONObject()
                val mediaId = item.optString("Id")
                if (mediaId.isBlank()) continue

                val mediaSourceId = playState.optString("MediaSourceId")
                if (mediaSourceId.isNotBlank()) mediaSourceIds[mediaId] = mediaSourceId
                if (playState.has("SubtitleStreamIndex") && !playState.isNull("SubtitleStreamIndex")) {
                    selectedSubtitleIndexes[mediaId] = playState.optInt("SubtitleStreamIndex")
                } else {
                    selectedSubtitleIndexes.remove(mediaId)
                }

                val series = item.optString("SeriesName")
                val title = item.optString("Name").ifBlank { "Jellyfin" }
                val season = item.optInt("ParentIndexNumber", -1)
                val episode = item.optInt("IndexNumber", -1)
                val displayTitle = if (series.isNotBlank()) {
                    val code = if (season >= 0 && episode >= 0) {
                        String.format(Locale.US, " · S%02dE%02d", season, episode)
                    } else ""
                    "$series$code · $title"
                } else title

                add(
                    PlaybackSession(
                        playerId = session.optString("Id").ifBlank {
                            session.optString("DeviceId").ifBlank { "jellyfin:$mediaId" }
                        },
                        client = session.optString("DeviceName").ifBlank { session.optString("Client") },
                        product = session.optString("Client"),
                        device = session.optString("DeviceName"),
                        state = if (playState.optBoolean("IsPaused", false)) "paused" else "playing",
                        title = displayTitle,
                        mediaId = mediaId,
                        sessionKey = session.optString("Id"),
                        position = playState.optLong("PositionTicks", 0L) / 10_000_000.0,
                    )
                )
            }
        }
    }

    override fun subtitleTracks(mediaId: String): List<SubtitleTrack> {
        val item = getJson(
            "/Users/${Uri.encode(userId)}/Items/${Uri.encode(mediaId)}",
            mapOf("Fields" to "MediaSources,MediaStreams,Path"),
        )
        val sources = item.optJSONArray("MediaSources") ?: JSONArray()
        val wantedSourceId = mediaSourceIds[mediaId].orEmpty()
        val source = findMediaSource(sources, wantedSourceId)
            ?: error("Jellyfin media item has no media source")
        val sourceId = source.optString("Id").ifBlank { wantedSourceId }
        check(sourceId.isNotBlank()) { "Jellyfin media source has no id" }
        mediaSourceIds[mediaId] = sourceId

        val streams = source.optJSONArray("MediaStreams") ?: JSONArray()
        val selectedIndex = selectedSubtitleIndexes[mediaId]
        return buildList {
            for (i in 0 until streams.length()) {
                val stream = streams.optJSONObject(i) ?: continue
                if (!stream.optString("Type").equals("Subtitle", ignoreCase = true)) continue

                val index = stream.optInt("Index", -1)
                if (index < 0) continue
                val codec = stream.optString("Codec").lowercase(Locale.US)
                val rawLanguage = stream.optString("Language")
                val language = normalizeLanguage(rawLanguage)
                val title = firstNonEmpty(
                    stream.optString("DisplayTitle"),
                    stream.optString("Title"),
                    if (language.isBlank()) "Subtitle $index" else language.uppercase(Locale.US),
                )

                add(
                    SubtitleTrack(
                        id = "jellyfin:$sourceId:$index",
                        source = if (stream.optBoolean("IsExternal", false)) "external" else "embedded",
                        language = language,
                        title = title,
                        codec = codec,
                        compatible = codec in TEXT_SUBTITLE_CODECS,
                        selected = selectedIndex == index,
                        providerData = mapOf(
                            "streamIndex" to index.toString(),
                            "mediaSourceId" to sourceId,
                            "languageTag" to inferLanguageTag(rawLanguage, title, language),
                            "forced" to stream.optBoolean("IsForced", false).toString(),
                        ),
                    )
                )
            }
        }
    }

    override fun subtitleTimeline(mediaId: String, track: SubtitleTrack): SubtitleTimeline {
        val sourceId = track.providerData["mediaSourceId"].orEmpty()
            .ifBlank { mediaSourceIds[mediaId].orEmpty() }
        val streamIndex = track.providerData["streamIndex"]?.toIntOrNull()
            ?: error("Missing Jellyfin subtitle stream index")
        check(sourceId.isNotBlank()) { "Missing Jellyfin media source id" }

        val cacheKey = "$mediaId:$sourceId:$streamIndex"
        synchronized(subtitleCache) {
            subtitleCache[cacheKey]?.let {
                diagnostics.add("Subtitle cache hit: media=$mediaId source=${track.source} cues=${it.cues.size}")
                return it
            }
        }

        val started = System.currentTimeMillis()
        diagnostics.add(
            "Jellyfin subtitle load started: media=$mediaId source=${track.source} " +
                "language=${track.language} codec=${track.codec}"
        )
        val payload = request(
            method = "GET",
            path = "/Videos/${Uri.encode(mediaId)}/${Uri.encode(sourceId)}/Subtitles/$streamIndex/Stream.srt",
            params = mapOf(
                "format" to "srt",
                "copyTimestamps" to "true",
                "addVttTimeMap" to "false",
            ),
            accept = "text/plain, text/srt, text/vtt, */*",
            readTimeoutMs = SUBTITLE_TIMEOUT_MS,
        )

        val cues = SubtitleParser.parse(payload.toString(StandardCharsets.UTF_8))
        check(cues.isNotEmpty()) { "Jellyfin returned a subtitle with no parseable cues" }
        val timeline = SubtitleTimeline(cues)
        synchronized(subtitleCache) { subtitleCache[cacheKey] = timeline }
        diagnostics.add(
            "Jellyfin subtitle ready: media=$mediaId cues=${cues.size} " +
                "elapsedMs=${System.currentTimeMillis() - started}"
        )
        return timeline
    }

    override fun clearSubtitleCache() = synchronized(subtitleCache) {
        subtitleCache.clear()
    }

    private fun getJson(path: String, params: Map<String, String>? = null): JSONObject =
        JSONObject(request("GET", path, params).toString(StandardCharsets.UTF_8))

    private fun getJsonArray(path: String): JSONArray =
        JSONArray(request("GET", path, null).toString(StandardCharsets.UTF_8))

    private fun request(
        method: String,
        path: String,
        params: Map<String, String>? = null,
        accept: String = "application/json",
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): ByteArray {
        val builder = Uri.parse(serverUrl + path).buildUpon()
        params?.forEach { (key, value) -> builder.appendQueryParameter(key, value) }

        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Authorization", authorizationHeader())

        val status = connection.responseCode
        val payload = readFully(if (status >= 400) connection.errorStream else connection.inputStream)
        connection.disconnect()
        if (status !in 200..299) {
            val detail = payload.toString(StandardCharsets.UTF_8).take(300)
            error("Jellyfin HTTP $status on $path${if (detail.isBlank()) "" else ": $detail"}")
        }
        return payload
    }

    private fun authorizationHeader(): String =
        "MediaBrowser Client=\"SideSubs\", Device=\"Android\", DeviceId=\"$deviceId\", " +
            "Version=\"1\", Token=\"$accessToken\""

    private fun findMediaSource(sources: JSONArray, wantedId: String): JSONObject? {
        if (wantedId.isNotBlank()) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                if (source.optString("Id") == wantedId) return source
            }
        }
        return sources.optJSONObject(0)
    }

    private fun inferLanguageTag(raw: String, title: String, base: String): String {
        val rawTag = raw.trim().lowercase(Locale.US).replace('_', '-')
        if (Regex("^[a-z]{2}-[a-z0-9]{2,3}$").matches(rawTag)) return rawTag
        val text = title.lowercase(Locale.US)
        return when (base) {
            "es" -> when {
                listOf("latin american", "latin america", "latinoamérica", "latinoamerica", "latam").any(text::contains) -> "es-419"
                listOf("mexico", "méxico", "mexican").any(text::contains) -> "es-mx"
                listOf("spain", "españa", "castilian", "castellano").any(text::contains) -> "es-es"
                else -> "es"
            }
            "pt" -> when {
                listOf("brazil", "brasil", "brazilian").any(text::contains) -> "pt-br"
                listOf("portugal", "european").any(text::contains) -> "pt-pt"
                else -> "pt"
            }
            "fr" -> when {
                listOf("canada", "canadian", "québec", "quebec").any(text::contains) -> "fr-ca"
                listOf("france", "french").any(text::contains) -> "fr-fr"
                else -> "fr"
            }
            "en" -> when {
                listOf("united kingdom", "british", "uk").any(text::contains) -> "en-gb"
                listOf("australia", "australian").any(text::contains) -> "en-au"
                listOf("canada", "canadian").any(text::contains) -> "en-ca"
                listOf("united states", "american", "usa").any(text::contains) -> "en-us"
                else -> "en"
            }
            "zh" -> when {
                listOf("traditional", "繁體", "繁体").any(text::contains) -> "zh-tw"
                listOf("simplified", "简体", "簡體").any(text::contains) -> "zh-cn"
                else -> "zh"
            }
            else -> base
        }
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
            lang.startsWith("zh") || lang.startsWith("zho") || lang.startsWith("chi") -> "zh"
            lang.startsWith("ja") || lang.startsWith("jpn") -> "ja"
            lang.startsWith("ko") || lang.startsWith("kor") -> "ko"
            lang.startsWith("nl") || lang.startsWith("nld") || lang.startsWith("dut") -> "nl"
            lang.startsWith("cs") || lang.startsWith("ces") || lang.startsWith("cze") -> "cs"
            lang.startsWith("da") || lang.startsWith("dan") -> "da"
            lang.startsWith("fi") || lang.startsWith("fin") -> "fi"
            lang.startsWith("hu") || lang.startsWith("hun") -> "hu"
            lang.startsWith("no") || lang.startsWith("nor") -> "no"
            lang.startsWith("pl") || lang.startsWith("pol") -> "pl"
            lang.startsWith("ro") || lang.startsWith("ron") || lang.startsWith("rum") -> "ro"
            lang.startsWith("sk") || lang.startsWith("slk") || lang.startsWith("slo") -> "sk"
            lang.startsWith("sv") || lang.startsWith("swe") -> "sv"
            lang.startsWith("tr") || lang.startsWith("tur") -> "tr"
            lang.startsWith("el") || lang.startsWith("ell") || lang.startsWith("gre") -> "el"
            lang.length > 2 -> lang.take(2)
            else -> lang
        }
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

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

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val SUBTITLE_TIMEOUT_MS = 120_000
        val TEXT_SUBTITLE_CODECS = setOf(
            "subrip", "srt", "ass", "ssa", "webvtt", "vtt", "mov_text", "text", "ttml"
        )
    }
}
