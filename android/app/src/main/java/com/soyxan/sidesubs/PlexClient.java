package com.soyxan.sidesubs;

import android.net.Uri;

import com.soyxan.sidesubs.PlexModels.Cue;
import com.soyxan.sidesubs.PlexModels.PlaybackSession;
import com.soyxan.sidesubs.PlexModels.SubtitleTimeline;
import com.soyxan.sidesubs.PlexModels.SubtitleTrack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class PlexClient {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int SUBTITLE_TIMEOUT_MS = 120_000;

    private final String baseUrl;
    private final String token;
    private final String clientIdentifier = "sidesubs-android-" + UUID.randomUUID();

    private final LinkedHashMap<String, SubtitleTimeline> subtitleCache =
        new LinkedHashMap<String, SubtitleTimeline>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SubtitleTimeline> eldest) {
                return size() > 32;
            }
        };

    PlexClient(String baseUrl, String token) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = token == null ? "" : token.trim();
        if (this.baseUrl.isEmpty()) throw new IllegalArgumentException("Plex URL is required");
        if (this.token.isEmpty()) throw new IllegalArgumentException("Plex token is required");
    }

    String serverUrl() {
        return baseUrl;
    }

    List<PlaybackSession> sessions() throws Exception {
        JSONObject root = getJson("/status/sessions", null);
        JSONObject container = root.optJSONObject("MediaContainer");
        JSONArray metadata = container == null ? null : container.optJSONArray("Metadata");
        List<PlaybackSession> sessions = new ArrayList<>();
        if (metadata == null) return sessions;

        for (int i = 0; i < metadata.length(); i++) {
            JSONObject item = metadata.optJSONObject(i);
            if (item == null) continue;
            JSONObject player = firstObject(item, "Player");
            if (player == null) continue;

            String playerId = firstNonEmpty(
                player.optString("machineIdentifier", ""),
                player.optString("uuid", ""),
                "fallback:" + player.optString("title", "") + "|" + player.optString("product", "")
            );
            String show = item.optString("grandparentTitle", "");
            String title = item.optString("title", "");
            int season = item.optInt("parentIndex", -1);
            int episode = item.optInt("index", -1);
            String displayTitle;
            if (!show.isEmpty()) {
                String code = season >= 0 && episode >= 0
                    ? String.format(Locale.US, " · S%02dE%02d", season, episode)
                    : "";
                displayTitle = show + code + " · " + title;
            } else {
                displayTitle = title.isEmpty() ? "Plex" : title;
            }

            sessions.add(new PlaybackSession(
                playerId,
                player.optString("title", ""),
                player.optString("product", ""),
                player.optString("device", ""),
                player.optString("state", "unknown"),
                displayTitle,
                stringValue(item, "ratingKey"),
                stringValue(item, "sessionKey"),
                item.optDouble("viewOffset", 0.0) / 1000.0
            ));
        }
        return sessions;
    }

    List<SubtitleTrack> subtitleTracks(String ratingKey) throws Exception {
        return metadata(ratingKey).tracks;
    }

    SubtitleTimeline subtitleTimeline(String ratingKey, SubtitleTrack track) throws Exception {
        String cacheKey = ratingKey + ":" + track.id;
        synchronized (subtitleCache) {
            SubtitleTimeline cached = subtitleCache.get(cacheKey);
            if (cached != null) return cached;
        }

        SubtitleTimeline timeline;
        if ("external".equals(track.source) && track.key != null && !track.key.isEmpty()) {
            timeline = fetchExternal(track);
        } else {
            timeline = fetchEmbedded(ratingKey, track);
        }

        synchronized (subtitleCache) {
            subtitleCache.put(cacheKey, timeline);
        }
        return timeline;
    }

    void clearSubtitleCache() {
        synchronized (subtitleCache) {
            subtitleCache.clear();
        }
    }

    private SubtitleTimeline fetchExternal(SubtitleTrack track) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("encoding", "utf-8");
        params.put("format", "srt");
        byte[] payload = request("GET", track.key, params, READ_TIMEOUT_MS, "*/*");
        List<Cue> cues = SubtitleParser.parse(new String(payload, StandardCharsets.UTF_8));
        if (cues.isEmpty()) throw new IllegalStateException("Plex returned an empty subtitle");
        return new SubtitleTimeline(cues);
    }

    private SubtitleTimeline fetchEmbedded(String ratingKey, SubtitleTrack track) throws Exception {
        MetadataData current = metadata(ratingKey);
        int oldStreamId = 0;
        for (SubtitleTrack candidate : current.tracks) {
            if (candidate.selected) {
                oldStreamId = candidate.streamId;
                break;
            }
        }

        String transcodeSession = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String playbackSession = UUID.randomUUID().toString().replace("-", "");

        Map<String, String> common = new LinkedHashMap<>();
        common.put("hasMDE", "1");
        common.put("path", "/library/metadata/" + ratingKey);
        common.put("mediaIndex", "0");
        common.put("partIndex", "0");
        common.put("fastSeek", "1");
        common.put("directPlay", "1");
        common.put("directStream", "1");
        common.put("directStreamAudio", "1");
        common.put("subtitleSize", "100");
        common.put("audioBoost", "100");
        common.put("videoQuality", "100");
        common.put("videoResolution", "4096x2160");
        common.put("location", "lan");
        common.put("mediaBufferSize", "50000");
        common.put("session", transcodeSession);
        common.put("subtitles", "sidecar");
        common.put("subtitleStreamID", Integer.toString(track.streamId));

        boolean changedSelection = oldStreamId != track.streamId;
        if (changedSelection) selectSubtitle(track.partId, track.streamId);

        try {
            Map<String, String> decision = new LinkedHashMap<>(common);
            decision.put("protocol", "hls");
            request(
                "GET",
                "/video/:/transcode/universal/decision",
                decision,
                READ_TIMEOUT_MS,
                "application/json",
                playbackSession
            );

            Map<String, String> subtitle = new LinkedHashMap<>(common);
            subtitle.put("protocol", "http");
            subtitle.put("copyts", "1");
            subtitle.put("offset", "0");
            byte[] payload = request(
                "GET",
                "/subtitles/:/transcode/universal/start",
                subtitle,
                SUBTITLE_TIMEOUT_MS,
                "*/*",
                playbackSession
            );
            List<Cue> cues = SubtitleParser.parse(new String(payload, StandardCharsets.UTF_8));
            if (cues.isEmpty()) {
                throw new IllegalStateException("Plex returned a subtitle with no parseable cues");
            }
            return new SubtitleTimeline(cues);
        } finally {
            if (changedSelection) {
                try {
                    selectSubtitle(track.partId, oldStreamId);
                } catch (Exception ignored) {
                    // Playback can continue even if Plex refuses the restore.
                }
            }
        }
    }

    private void selectSubtitle(int partId, int streamId) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("allParts", "1");
        params.put("subtitleStreamID", Integer.toString(streamId));
        request("PUT", "/library/parts/" + partId, params, READ_TIMEOUT_MS, "*/*");
    }

    private MetadataData metadata(String ratingKey) throws Exception {
        JSONObject root = getJson("/library/metadata/" + ratingKey, null);
        JSONObject container = root.optJSONObject("MediaContainer");
        JSONArray metadata = container == null ? null : container.optJSONArray("Metadata");
        JSONObject item = metadata == null ? null : metadata.optJSONObject(0);
        if (item == null) throw new IllegalStateException("Plex metadata not found");

        JSONArray mediaArray = item.optJSONArray("Media");
        JSONObject media = mediaArray == null ? null : mediaArray.optJSONObject(0);
        JSONArray parts = media == null ? null : media.optJSONArray("Part");
        JSONObject part = parts == null ? null : parts.optJSONObject(0);
        if (part == null) throw new IllegalStateException("Plex media item has no parts");

        int partId = intValue(part, "id", 0);
        JSONArray streams = part.optJSONArray("Stream");
        List<SubtitleTrack> tracks = new ArrayList<>();
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null || intValue(stream, "streamType", 0) != 3) continue;

                int streamId = intValue(stream, "id", 0);
                String codec = stream.optString("codec", "").toLowerCase(Locale.US);
                String key = stream.optString("key", "");
                String source = key.isEmpty() ? "embedded" : "external";
                String language = normalizeLanguage(firstNonEmpty(
                    stream.optString("languageCode", ""),
                    stream.optString("language", "")
                ));
                String title = firstNonEmpty(
                    stream.optString("title", ""),
                    stream.optString("extendedDisplayTitle", ""),
                    stream.optString("displayTitle", ""),
                    language.isEmpty() ? "Subtitle " + streamId : language.toUpperCase(Locale.US)
                );
                boolean compatible =
                    codec.equals("subrip") || codec.equals("srt") || codec.equals("ass")
                    || codec.equals("ssa") || codec.equals("webvtt") || codec.equals("vtt")
                    || codec.equals("mov_text") || codec.equals("text");

                tracks.add(new SubtitleTrack(
                    streamId,
                    partId,
                    source,
                    language,
                    title,
                    codec,
                    key,
                    compatible,
                    intValue(stream, "selected", 0) == 1 || stream.optBoolean("selected", false)
                ));
            }
        }
        return new MetadataData(tracks);
    }

    private JSONObject getJson(String path, Map<String, String> params) throws Exception {
        byte[] payload = request("GET", path, params, READ_TIMEOUT_MS, "application/json");
        return new JSONObject(new String(payload, StandardCharsets.UTF_8));
    }

    private byte[] request(
        String method,
        String path,
        Map<String, String> params,
        int readTimeoutMs,
        String accept
    ) throws Exception {
        return request(method, path, params, readTimeoutMs, accept, null);
    }

    private byte[] request(
        String method,
        String path,
        Map<String, String> params,
        int readTimeoutMs,
        String accept,
        String playbackSessionId
    ) throws Exception {
        Uri.Builder builder = Uri.parse(path.startsWith("http") ? path : baseUrl + path).buildUpon();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.appendQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        builder.appendQueryParameter("X-Plex-Token", token);

        HttpURLConnection connection = (HttpURLConnection) new URL(builder.build().toString()).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("X-Plex-Token", token);
        connection.setRequestProperty("X-Plex-Client-Identifier", clientIdentifier);
        connection.setRequestProperty("X-Plex-Product", "SideSubs");
        connection.setRequestProperty("X-Plex-Version", "1");
        connection.setRequestProperty("X-Plex-Platform", "Android");
        connection.setRequestProperty("X-Plex-Device", "SideSubs");
        connection.setRequestProperty("Accept", accept);
        if (playbackSessionId != null) {
            connection.setRequestProperty("X-Plex-Session-Identifier", playbackSessionId);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] payload = readFully(stream);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            String detail = new String(payload, StandardCharsets.UTF_8);
            if (detail.length() > 300) detail = detail.substring(0, 300);
            throw new IllegalStateException("Plex HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
        }
        return payload;
    }

    private static byte[] readFully(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static JSONObject firstObject(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof JSONArray && ((JSONArray) value).length() > 0) {
            return ((JSONArray) value).optJSONObject(0);
        }
        return null;
    }

    private static String normalizeBaseUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        return url.replaceAll("/+$", "");
    }

    private static String normalizeLanguage(String value) {
        String lang = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (lang.startsWith("es") || lang.startsWith("spa")) return "es";
        if (lang.startsWith("en") || lang.startsWith("eng")) return "en";
        if (lang.startsWith("fr") || lang.startsWith("fre") || lang.startsWith("fra")) return "fr";
        if (lang.startsWith("de") || lang.startsWith("ger") || lang.startsWith("deu")) return "de";
        if (lang.startsWith("it") || lang.startsWith("ita")) return "it";
        if (lang.startsWith("pt") || lang.startsWith("por")) return "pt";
        return lang.length() > 2 ? lang.substring(0, 2) : lang;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String stringValue(JSONObject object, String key) {
        Object value = object.opt(key);
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
    }

    private static int intValue(JSONObject object, String key, int fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class MetadataData {
        final List<SubtitleTrack> tracks;
        MetadataData(List<SubtitleTrack> tracks) {
            this.tracks = tracks;
        }
    }
}
