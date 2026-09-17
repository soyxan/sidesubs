from __future__ import annotations

import contextvars
import threading
import time

from fastapi import Request

from app import main


_original_select_session = main.select_session
_lock = threading.Lock()
_clocks: dict[str, dict[str, float | str]] = {}
_selected_player_id: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "selected_player_id",
    default=None,
)

RAW_CHANGE_EPSILON_SECONDS = 0.20
SEEK_THRESHOLD_SECONDS = 2.5


def _player_id(session) -> str:
    player = getattr(session, "player", None)
    machine_id = getattr(player, "machineIdentifier", None)
    if machine_id:
        return str(machine_id)

    title = getattr(player, "title", None) or ""
    product = getattr(player, "product", None) or ""
    device = getattr(player, "device", None) or ""
    return f"fallback:{title}|{product}|{device}"


def _session_key(session) -> str:
    rating_key = getattr(session, "ratingKey", None) or "unknown-media"
    session_key = getattr(session, "sessionKey", None) or ""
    return f"{_player_id(session)}:{rating_key}:{session_key}"


def _smooth_position(session) -> None:
    player = getattr(session, "player", None)
    state = (getattr(player, "state", "") or "unknown").casefold()
    raw_position = int(getattr(session, "viewOffset", 0) or 0) / 1000.0
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
                position = predicted
            elif raw_change < -SEEK_THRESHOLD_SECONDS:
                position = raw_position
            elif raw_change > elapsed + SEEK_THRESHOLD_SECONDS:
                position = raw_position
            else:
                position = max(predicted, raw_position)

        _clocks[key] = {
            "position": position,
            "raw_position": raw_position,
            "time": now,
            "state": state,
        }

    session.viewOffset = int(position * 1000)


def select_session_with_smooth_clock(sessions):
    items = list(sessions)
    requested_player_id = _selected_player_id.get()

    # For normal app status calls selection is explicit. An empty string means
    # the browser has not selected a player, so do not fall back to any session.
    if requested_player_id is not None:
        if not requested_player_id:
            return None
        session = next((item for item in items if _player_id(item) == requested_player_id), None)
    else:
        # Keep diagnostics useful when called directly.
        session = _original_select_session(items)

    if session is not None:
        _smooth_position(session)
    return session


main.select_session = select_session_with_smooth_clock


@main.app.get("/api/sessions")
def sessions():
    items = list(main.plex_server().sessions())
    result = []
    for session in items:
        data = main.serialize_session(session)
        data["player_id"] = _player_id(session)
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
