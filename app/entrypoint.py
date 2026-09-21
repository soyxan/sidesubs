from __future__ import annotations

import contextvars
import threading
import time

from fastapi import Request

from app import main
from app.domain import PlaybackSession


_original_select_session = main.select_session
_lock = threading.Lock()
_clocks: dict[str, dict[str, float | str]] = {}
_selected_player_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "selected_player_id",
    default=None,
)

RAW_CHANGE_EPSILON_SECONDS = 0.20
SEEK_THRESHOLD_SECONDS = 2.5


def _session_key(session: PlaybackSession) -> str:
    return session.id


def _smooth_position(session: PlaybackSession) -> None:
    state = (session.state or "unknown").casefold()
    raw_position = float(session.position)
    now = time.monotonic()
    key = _session_key(session)

    with _lock:
        previous = _clocks.get(key)

        if state != "playing":
            position = raw_position
            _clocks[key] = {
                "position": position,
                "raw_position": raw_position,
                "time": now,
                "state": state,
            }
            session.position = position
            return

        pending_raw = None
        pending_time = None

        if previous is None or previous.get("state") != "playing":
            position = raw_position
        else:
            elapsed = max(0.0, now - float(previous["time"]))
            predicted = float(previous["position"]) + elapsed
            previous_raw = float(previous["raw_position"])
            raw_change = raw_position - previous_raw
            pending_raw = previous.get("pending_backward_raw")
            pending_time = previous.get("pending_backward_time")
            confirmed_backward_seek = False

            # PMS can briefly emit one stale viewOffset several seconds behind
            # playback. Do not accept that single sample as a seek. If the next
            # raw sample continues from the lower timeline, confirm the seek;
            # if it jumps back to the old timeline, treat the first sample as
            # transient jitter.
            if pending_raw is not None and pending_time is not None:
                # A stale PMS viewOffset is often repeated unchanged for a
                # number of polls. That must not confirm a seek. A genuine
                # backward seek is accepted only once the lower timeline itself
                # starts advancing.
                lower_timeline_progress = raw_position - float(pending_raw)
                still_below_smoothed_clock = predicted - raw_position > SEEK_THRESHOLD_SECONDS
                if lower_timeline_progress >= 0.5 and still_below_smoothed_clock:
                    position = raw_position
                    confirmed_backward_seek = True
                    pending_raw = None
                    pending_time = None
                elif raw_position >= predicted - SEEK_THRESHOLD_SECONDS:
                    pending_raw = None
                    pending_time = None

            if not confirmed_backward_seek:
                if abs(raw_change) <= RAW_CHANGE_EPSILON_SECONDS:
                    position = predicted
                elif raw_change < -SEEK_THRESHOLD_SECONDS:
                    position = predicted
                    pending_raw = raw_position
                    pending_time = now
                elif raw_change < 0:
                    position = predicted
                elif raw_change > elapsed + SEEK_THRESHOLD_SECONDS:
                    position = raw_position
                else:
                    position = max(predicted, raw_position)

        clock = {
            "position": position,
            "raw_position": raw_position,
            "time": now,
            "state": state,
        }
        if pending_raw is not None:
            clock["pending_backward_raw"] = pending_raw
            clock["pending_backward_time"] = pending_time
        _clocks[key] = clock

    session.position = position


def select_session_with_smooth_clock(sessions):
    items = list(sessions)
    requested_player_id = _selected_player_id.get()

    if requested_player_id is not None:
        if not requested_player_id:
            return None
        session = next((item for item in items if item.player_id == requested_player_id), None)
    else:
        session = _original_select_session(items)

    if session is not None:
        _smooth_position(session)
    return session


main.select_session = select_session_with_smooth_clock


@main.app.get("/api/sessions")
def sessions():
    items = main.provider().list_sessions()
    result = []
    for session in items:
        data = main.serialize_session(session)
        data["player_id"] = session.player_id
        result.append(data)
    return {"ok": True, "sessions": result}


@main.app.middleware("http")
async def selected_session_context(request: Request, call_next):
    if request.url.path != "/api/status":
        return await call_next(request)

    player_id = request.query_params.get("player_id", "")
    token = _selected_player_id.set(player_id)
    try:
        return await call_next(request)
    finally:
        _selected_player_id.reset(token)


app = main.app
