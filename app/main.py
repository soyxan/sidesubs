from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Iterable

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse

from app.domain import Cue, PlaybackSession, SubtitleTrack
from app.providers.base import MediaProvider
from app.providers.plex import PlexProvider
from app.subtitles import cue_pair, normalize_language


logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO").upper(),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("sidesubs")


@dataclass(frozen=True)
class Settings:
    media_provider: str
    plex_url: str
    plex_token: str
    plex_client_filter: str
    poll_interval_ms: int

    @classmethod
    def from_env(cls) -> "Settings":
        provider_name = os.getenv("MEDIA_PROVIDER", "plex").strip().casefold() or "plex"
        if provider_name != "plex":
            raise RuntimeError(f"MEDIA_PROVIDER '{provider_name}' is not implemented yet")

        token = os.getenv("PLEX_TOKEN", "").strip()
        if not token:
            raise RuntimeError("PLEX_TOKEN is required")

        poll_ms = int(os.getenv("POLL_INTERVAL_MS", "750"))
        poll_ms = max(250, min(poll_ms, 5000))

        return cls(
            media_provider=provider_name,
            plex_url=os.getenv("PLEX_URL", "http://host.docker.internal:32400").rstrip("/"),
            plex_token=token,
            plex_client_filter=os.getenv("PLEX_CLIENT_FILTER", "").strip(),
            poll_interval_ms=poll_ms,
        )


app = FastAPI(title="SideSubs", docs_url=None, redoc_url=None)
STATIC_DIR = Path(__file__).parent / "static"

APP_ID = "SideSubs"
API_VERSION = 1
APP_VERSION = os.getenv("SIDESUBS_VERSION", "dev").strip() or "dev"

logger.info("Starting %s version=%s api_version=%s", APP_ID, APP_VERSION, API_VERSION)


@lru_cache(maxsize=1)
def settings() -> Settings:
    cfg = Settings.from_env()
    logger.info(
        "Configuration loaded: provider=%s plex_url=%s client_filter=%s poll_ms=%s",
        cfg.media_provider,
        cfg.plex_url,
        cfg.plex_client_filter or "<none>",
        cfg.poll_interval_ms,
    )
    return cfg


@lru_cache(maxsize=1)
def provider() -> MediaProvider:
    cfg = settings()
    if cfg.media_provider == "plex":
        return PlexProvider(cfg.plex_url, cfg.plex_token)
    raise RuntimeError(f"Unsupported media provider: {cfg.media_provider}")


def select_session(sessions: Iterable[PlaybackSession]) -> PlaybackSession | None:
    items = list(sessions)
    if not items:
        return None

    client_filter = settings().plex_client_filter.casefold()
    if client_filter:
        for session in items:
            if client_filter in " ".join((session.client, session.product, session.device)).casefold():
                return session

    for session in items:
        if session.state == "playing":
            return session
    return items[0]


def choose_subtitle_track(
    tracks: list[SubtitleTrack],
    preferred_language: str,
    requested_id: str,
) -> SubtitleTrack | None:
    compatible = [track for track in tracks if track.compatible]

    if requested_id:
        selected = next((track for track in compatible if track.id == requested_id), None)
        if selected is not None:
            return selected

    language = normalize_language(preferred_language)
    if language:
        matches = [track for track in compatible if normalize_language(track.language) == language]
        return matches[0] if matches else None

    return compatible[0] if compatible else None


def serialize_cue(cue: Cue | None) -> dict | None:
    if cue is None:
        return None
    return {"start": cue.start, "end": cue.end, "text": cue.text}


def serialize_session(session: PlaybackSession) -> dict:
    return {
        "client": session.client or None,
        "product": session.product or None,
        "device": session.device or None,
        "state": session.state,
        "title": session.title,
        "position": session.position,
        "rating_key": session.rating_key or None,
    }


def build_diagnostics() -> dict:
    media_provider = provider()
    sessions = media_provider.list_sessions()
    session = select_session(sessions)
    result = {
        "ok": True,
        **media_provider.diagnostics(),
        "session_found": session is not None,
        "session_count": len(sessions),
        "sessions": [serialize_session(item) for item in sessions],
        "selected_client": None,
        "state": None,
        "position": None,
        "subtitle_tracks": [],
        "subtitle_error": None,
    }
    if session is None:
        return result

    result["selected_client"] = session.client or session.product or session.device or media_provider.name
    result["state"] = session.state
    result["position"] = session.position
    try:
        result["subtitle_tracks"] = [track.as_dict() for track in media_provider.list_subtitle_tracks(session)]
    except Exception as exc:
        result["subtitle_error"] = str(exc)
    return result


@app.get("/")
def index():
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/health")
def health():
    return {"ok": True}


@app.get("/api/app-info")
def app_info():
    return {
        "app": APP_ID,
        "api_version": API_VERSION,
        "version": APP_VERSION,
        "media_provider": settings().media_provider,
    }


@app.get("/api/debug")
def debug():
    try:
        return build_diagnostics()
    except Exception as exc:
        logger.exception("Diagnostics failed")
        return JSONResponse(status_code=500, content={"ok": False, "error": f"{type(exc).__name__}: {exc}"})


@app.get("/api/status")
def status(preferred_language: str = "", subtitle_id: str = "", delay_ms: int = 0):
    try:
        media_provider = provider()
        session = select_session(media_provider.list_sessions())
        if session is None:
            return {"ok": True, "active": False, "poll_interval_ms": settings().poll_interval_ms}

        current = next_cue = None
        cues: tuple[Cue, ...] = ()
        tracks: list[SubtitleTrack] = []
        selected_track = None
        subtitle_error = None

        try:
            tracks = media_provider.list_subtitle_tracks(session)
            selected_track = choose_subtitle_track(tracks, preferred_language, subtitle_id)
            if selected_track:
                cues = media_provider.subtitle_cues(session, selected_track, session.position)
                if cues:
                    safe_delay_ms = max(0, min(int(delay_ms), 5000))
                    effective_position = max(0.0, session.position - safe_delay_ms / 1000.0)
                    current, next_cue = cue_pair(cues, effective_position)
        except Exception as exc:
            subtitle_error = "Subtitle stream temporarily unavailable"
            logger.warning("Subtitle loading failed: %s", exc)

        logger.debug(
            "Provider=%s client=%s state=%s position=%.3fs selected_subtitle=%s subtitle_error=%s",
            media_provider.name,
            session.client or session.product or session.device or media_provider.name,
            session.state,
            session.position,
            selected_track.id if selected_track else None,
            subtitle_error,
        )

        return {
            "ok": True,
            "active": True,
            "title": session.title,
            "state": session.state,
            "client": session.client or session.product or session.device or media_provider.name,
            "position": session.position,
            "subtitle_position": max(0.0, session.position - max(0, min(int(delay_ms), 5000)) / 1000.0),
            "rating_key": session.rating_key or None,
            "media_found": True,
            "subtitle_tracks": [track.as_dict() for track in tracks],
            "selected_subtitle_id": selected_track.id if selected_track else None,
            "subtitle_found": selected_track is not None,
            "subtitle_error": subtitle_error,
            "current": serialize_cue(current),
            "next": serialize_cue(next_cue),
            "poll_interval_ms": settings().poll_interval_ms,
        }

    except Exception as exc:
        logger.exception("Status update failed")
        return JSONResponse(status_code=500, content={"ok": False, "error": f"{type(exc).__name__}: {exc}"})
