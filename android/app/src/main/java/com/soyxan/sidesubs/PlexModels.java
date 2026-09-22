package com.soyxan.sidesubs;

import java.util.Collections;
import java.util.Map;

final class PlexModels {
    private PlexModels() {}

    static final class Cue {
        final double start;
        final double end;
        final String text;

        Cue(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    static final class PlaybackSession {
        final String playerId;
        final String client;
        final String product;
        final String device;
        final String state;
        final String title;
        final String ratingKey;
        final String sessionKey;
        final double position;

        PlaybackSession(
            String playerId,
            String client,
            String product,
            String device,
            String state,
            String title,
            String ratingKey,
            String sessionKey,
            double position
        ) {
            this.playerId = playerId;
            this.client = client;
            this.product = product;
            this.device = device;
            this.state = state;
            this.title = title;
            this.ratingKey = ratingKey;
            this.sessionKey = sessionKey;
            this.position = position;
        }

        String displayClient() {
            if (client != null && !client.isEmpty()) return client;
            if (product != null && !product.isEmpty()) return product;
            return device == null ? "Plex" : device;
        }

        String clockKey() {
            return playerId + ":" + ratingKey + ":" + sessionKey;
        }
    }

    static final class SubtitleTrack {
        final String id;
        final int streamId;
        final int partId;
        final String source;
        final String language;
        final String title;
        final String codec;
        final String key;
        final boolean compatible;
        final boolean selected;

        SubtitleTrack(
            int streamId,
            int partId,
            String source,
            String language,
            String title,
            String codec,
            String key,
            boolean compatible,
            boolean selected
        ) {
            this.streamId = streamId;
            this.partId = partId;
            this.id = "plex:" + streamId;
            this.source = source;
            this.language = language;
            this.title = title;
            this.codec = codec;
            this.key = key;
            this.compatible = compatible;
            this.selected = selected;
        }

        String label() {
            String lang = language == null || language.isEmpty() ? "" : language.toUpperCase() + " · ";
            String kind = "external".equals(source) ? "External" : "MKV";
            return lang + kind + " · " + title;
        }
    }

    static final class SubtitleTimeline {
        final java.util.List<Cue> cues;
        final Map<String, Object> info;

        SubtitleTimeline(java.util.List<Cue> cues) {
            this(cues, Collections.emptyMap());
        }

        SubtitleTimeline(java.util.List<Cue> cues, Map<String, Object> info) {
            this.cues = cues;
            this.info = info;
        }
    }
}
