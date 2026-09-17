from __future__ import annotations

import threading
import time

from app import main


_original_select_session = main.select_session
_lock = threading.Lock()
_clocks: dict[str, dict[str, float | str]] = {}

# If Plex's reported position differs from our local estimate by more than this,
# treat it as a real seek/resync instead of normal coarse viewOffset updates.
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

        # Paused/stopped/buffering: Plex's value is authoritative and the local
        # clock must not advance.
        if state != "playing":
            _clocks[key] = {
                "position": raw_position,
                "raw_position": raw_position,
                "time": now,
                "state": state,
            }
            return

        if previous is None or previous.get("state") != "playing":
            position = raw_position
        else:
            elapsed = max(0.0, now - float(previous["time"]))
            predicted = float(previous["position"]) + elapsed
            delta = raw_position - predicted

            if abs(delta) >= SEEK_THRESHOLD_SECONDS:
                # Genuine seek or a large resync from Plex.
                position = raw_position
            else:
                # Plex often reports viewOffset in coarse/stale steps. Advance
                # locally between reports and never snap backwards for a small
                # amount of normal reporting jitter.
                position = max(predicted, raw_position)

        _clocks[key] = {
            "position": position,
            "raw_position": raw_position,
            "time": now,
            "state": state,
        }

    # main.status() reads viewOffset after select_session(), so replacing it here
    # transparently gives the rest of the existing application a smooth clock.
    session.viewOffset = int(position * 1000)


def select_session_with_smooth_clock(sessions):
    session = _original_select_session(sessions)
    if session is not None:
        _smooth_position(session)
    return session


main.select_session = select_session_with_smooth_clock
app = main.app
