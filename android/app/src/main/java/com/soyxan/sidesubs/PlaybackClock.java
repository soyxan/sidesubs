package com.soyxan.sidesubs;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;

final class PlaybackClock {
    private static final double RAW_CHANGE_EPSILON = 0.20;
    private static final double SEEK_THRESHOLD = 2.5;

    private static final class State {
        double position;
        double rawPosition;
        long timeMs;
        String playbackState;
        Double pendingBackwardRaw;
    }

    private final Map<String, State> states = new HashMap<>();

    synchronized double smooth(String key, double rawPosition, String playbackState) {
        long now = SystemClock.elapsedRealtime();
        String normalized = playbackState == null ? "unknown" : playbackState.toLowerCase();
        State previous = states.get(key);

        if (!"playing".equals(normalized)) {
            State next = new State();
            next.position = rawPosition;
            next.rawPosition = rawPosition;
            next.timeMs = now;
            next.playbackState = normalized;
            states.put(key, next);
            return rawPosition;
        }

        double position;
        Double pending = null;

        if (previous == null || !"playing".equals(previous.playbackState)) {
            position = rawPosition;
        } else {
            double elapsed = Math.max(0, now - previous.timeMs) / 1000.0;
            double predicted = previous.position + elapsed;
            double rawChange = rawPosition - previous.rawPosition;
            pending = previous.pendingBackwardRaw;
            boolean confirmedBackward = false;

            if (pending != null) {
                double lowerProgress = rawPosition - pending;
                boolean stillBelowClock = predicted - rawPosition > SEEK_THRESHOLD;
                if (lowerProgress >= 0.5 && stillBelowClock) {
                    position = rawPosition;
                    confirmedBackward = true;
                    pending = null;
                } else {
                    position = predicted;
                    if (rawPosition >= predicted - SEEK_THRESHOLD) pending = null;
                }
            } else {
                position = predicted;
            }

            if (!confirmedBackward) {
                if (Math.abs(rawChange) <= RAW_CHANGE_EPSILON) {
                    position = predicted;
                } else if (rawChange < -SEEK_THRESHOLD) {
                    position = predicted;
                    pending = rawPosition;
                } else if (rawChange < 0) {
                    position = predicted;
                } else if (rawChange > elapsed + SEEK_THRESHOLD) {
                    position = rawPosition;
                } else {
                    position = Math.max(predicted, rawPosition);
                }
            }
        }

        State next = new State();
        next.position = position;
        next.rawPosition = rawPosition;
        next.timeMs = now;
        next.playbackState = normalized;
        next.pendingBackwardRaw = pending;
        states.put(key, next);
        return position;
    }

    synchronized void clear() {
        states.clear();
    }
}
