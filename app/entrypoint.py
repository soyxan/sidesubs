from __future__ import annotations

import threading
import time

from app import main


_original_select_session = main.select_session
_lock = threading.Lock()
_clocks: dict[str, dict[str, float | str]] = {}

# Ignore tiny changes in Plex's reported viewOffset. A real seek is detected from
# a meaningful change in Plex's raw value, not merely from the raw value becoming
# stale compared with our locally interpolated clock.
RAW_CHANGE_EPSILON_SECONDS = 0.20
SEEK_THRESHOLD_SECONDS = 2.5


def _session_key(session) -> str:
    player = getattr(session, "player", None)
    player_id = (
        getattr(player, "machineIdentifier", None)
        or getattr(player, "title", None)
        or getattr(player, "device", None)
        or "unknown-player"
    )
    rating_key = getattr(session, "ratingKey", None) or "unknown-media"
    session_key = getattr(session, "sessionKey", None) or ""
    return f"{player_id}:{rating_key}:{session_key}"


def _smooth_position(session) -> None:
    player = getattr(session, "player", None)
    state = (getattr(player, "state", "") or "unknown").casefold()
    raw_position = int(getattr(session, "viewOffset", 0) or 0) / 1000.0
    now = time.monotonic()
    key = _session_key(session)

    with _lock:
        previous = _clocks.get(key)

        # Paused/stopped/buffering: Plex is authoritative and the local clock
        # must not advance.
        if state != "playing":
            position = raw_position
            _clocks[key] = {
                "position": position,
                "raw_position": raw_position,
                "time": now,
                "state": state,
            }
            session.viewOffset = int(position * 1000)
            return

        if previous is None or previous.get("state") != "playing":
            position = raw_position
        else:
            elapsed = max(0.0, now - float(previous["time"]))
            predicted = float(previous["position"]) + elapsed
            previous_raw = float(previous["raw_position"])
            raw_change = raw_position - previous_raw

            if abs(raw_change) <= RAW_CHANGE_EPSILON_SECONDS:
                # Plex is still returning the same stale viewOffset. Do NOT
                # mistake the growing gap to the local clock for a backwards
                # seek; simply keep the local clock moving forward.
                position = predicted
            elif raw_change < -SEEK_THRESHOLD_SECONDS:
                # Plex's raw position genuinely moved backwards: real seek.
                position = raw_position
            elif raw_change > elapsed + SEEK_THRESHOLD_SECONDS:
                # Plex jumped forward much farther than normal playback could
                # have advanced since the previous request: real forward seek.
                position = raw_position
            else:
                # Normal coarse Plex update catching up with playback. Never
                # move backwards; use whichever position is farther ahead.
                position = max(predicted, raw_position)

        _clocks[key] = {
            "position": position,
            "raw_position": raw_position,
            "time": now,
            "state": state,
        }

    # main.status() reads viewOffset after select_session(), so replacing it here
    # transparently gives the rest of the application the smoothed position.
    session.viewOffset = int(position * 1000)


def select_session_with_smooth_clock(sessions):
    session = _original_select_session(sessions)
    if session is not None:
        _smooth_position(session)
    return session


main.select_session = select_session_with_smooth_clock
app = main.app
